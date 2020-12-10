package co.ledger.wallet.daemon.services

import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill}
import akka.dispatch.{PriorityGenerator, UnboundedStablePriorityMailbox}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import cats.implicits._
import co.ledger.core._
import co.ledger.core.implicits._
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.database.DaemonCache
import co.ledger.wallet.daemon.exceptions.{AccountNotFoundException, SyncOnGoingException}
import co.ledger.wallet.daemon.models.Account._
import co.ledger.wallet.daemon.models.Wallet._
import co.ledger.wallet.daemon.models.{AccountInfo, PoolInfo}
import co.ledger.wallet.daemon.modules.PublisherModule
import co.ledger.wallet.daemon.schedulers.observers.SynchronizationResult
import co.ledger.wallet.daemon.services.AccountOperationsPublisher.PoolName
import co.ledger.wallet.daemon.services.AccountSynchronizer._
import co.ledger.wallet.daemon.services.AccountSynchronizerWatchdog._
import co.ledger.wallet.daemon.utils.AkkaUtils
import com.twitter.util.{Duration, Timer}
import com.typesafe.config.Config
import javax.inject.{Inject, Named, Singleton}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.postfixOps
import scala.util.Success
import scala.util.control.NonFatal

/**
  * This module is responsible to maintain account updated
  * It's pluggable to external trigger
  *
  * @param scheduler used to schedule all the operations in ASM
  */
@Singleton
class AccountSynchronizerManager @Inject()(
                                            daemonCache: DaemonCache,
                                            @Named("AccountSynchronizerWatchdog") synchronizerWatchdog: ActorRef,
                                            scheduler: Timer
                                          )
  extends DaemonService {

  import co.ledger.wallet.daemon.context.ApplicationContext.IOPool
  import com.twitter.util.Duration

  // When we start ASM, we register the existing accountsSuccess
  // We periodically try to register account just in case there is new account created
  lazy private val periodicRegisterAccount =
  scheduler.schedule(Duration.fromSeconds(DaemonConfiguration.Synchronization.syncAccountRegisterInterval))(registerAccounts)

  // should be called after the instantiation of this class
  def start(): Future[Unit] = {
    registerAccounts.andThen {
      case Success(_) =>
        periodicRegisterAccount
        info("Started account synchronizer manager")
    }
  }

  def registerAccount(wallet: Wallet, account: Account, accountInfo: AccountInfo): Unit = {
    synchronizerWatchdog ! RegisterAccount(wallet, account, accountInfo)
  }

  // return None if account info not found
  def getSyncStatus(accountInfo: AccountInfo): Future[Option[SyncStatus]] = {
    ask(synchronizerWatchdog, GetStatus(accountInfo))(Timeout(10 seconds)).mapTo[Option[SyncStatus]]
  }

  def ongoingSyncs(): Future[List[(AccountInfo, SyncStatus)]] = {
    ask(synchronizerWatchdog, GetStatuses)(Timeout(10 seconds)).mapTo[List[(AccountInfo, SyncStatus)]]
  }

  def syncAccount(accountInfo: AccountInfo): Future[SynchronizationResult] = {
    implicit val timeout: Timeout = Timeout(60 seconds)
    ask(synchronizerWatchdog, ForceSynchronization(accountInfo)).mapTo[SyncStatus]
      .map(synchronizationResult(accountInfo))
  }

  def resyncAccount(accountInfo: AccountInfo): Unit = {
    synchronizerWatchdog ! Resync(accountInfo)
  }

  def unregisterPool(poolInfo: PoolInfo): Future[Unit] = {
    implicit val timeout: Timeout = Timeout(60 seconds)

    ask(synchronizerWatchdog, UnregisterPool(poolInfo.poolName)).mapTo[Unit]
  }

  def syncPool(poolInfo: PoolInfo): Future[Seq[SynchronizationResult]] =
    daemonCache.withWalletPool(poolInfo)(
      pool =>
        for {
          wallets <- pool.wallets
          syncResults <- wallets.toList.traverse { wallet =>
            wallet.accounts.flatMap(
              _.toList.traverse(
                account =>
                  syncAccount(
                    AccountInfo(
                      account.getIndex,
                      wallet.getName,
                      pool.name
                    )
                  )
              )
            )
          }
        } yield syncResults.flatten
    )

  def syncAllRegisteredAccounts(): Future[Seq[SynchronizationResult]] = {
    implicit val timeout: Timeout = Timeout.durationToTimeout(FiniteDuration(Long.MaxValue, TimeUnit.NANOSECONDS))
    ask(synchronizerWatchdog, ForceAllSynchronizations).mapTo[Seq[(AccountInfo, SyncStatus)]]
      .map(_.map((element: (AccountInfo, SyncStatus)) => synchronizationResult(element._1)(element._2)))
  }

  // Maybe to be called periodically to discover new account
  private def registerAccounts: Future[Unit] = {
    for {
      pools <- daemonCache.getAllPools
      wallets <- Future.sequence(pools.map { pool => pool.wallets.map(_.map(w => (pool, w))) }).map(_.flatten)
      accounts <- Future.sequence(wallets.map { case (pool, wallet) =>
        for {
          accounts <- wallet.accounts
        } yield accounts.map(account => (pool, wallet, account))
      }).map(_.flatten)
    } yield {
      accounts.foreach {
        case (pool, wallet, account) =>
          val accountInfo = AccountInfo(
            walletName = wallet.getName,
            poolName = pool.name,
            accountIndex = account.getIndex
          )
          registerAccount(wallet, account, accountInfo)
      }
    }
  }

  def close(after: Duration): Unit = {
    periodicRegisterAccount.cancel()
    periodicRegisterAccount.close(after)
    synchronizerWatchdog ! PoisonPill
  }

  private def synchronizationResult(accountInfo: AccountInfo)(syncStatus: SyncStatus): SynchronizationResult = {

    val syncResult = SynchronizationResult(accountInfo.accountIndex, accountInfo.walletName, accountInfo.poolName, _)

    syncStatus match {
      case Synced(_) => syncResult(true)
      case _ => syncResult(false)
    }
  }
}

class AccountSynchronizerWatchdog(
                                   cache: DaemonCache,
                                   scheduler: Timer,
                                   synchronizer: ActorRef,
                                   publisherFactory: PublisherModule.OperationsPublisherFactory
                                 ) extends Actor with ActorLogging {
  implicit val ec: ExecutionContext = context.dispatcher

  case class AccountData(account: Account, wallet: Wallet, syncStatus: SyncStatus, publisher: ActorRef)

  val accounts: mutable.Map[AccountInfo, AccountData] = new mutable.HashMap()
  val ongoingForceSyncs: mutable.Map[AccountInfo, Promise[SyncStatus]] = new mutable.HashMap()
  val accountsPublisherIndex: mutable.Map[ActorRef, AccountInfo] = new mutable.HashMap()

  override def preStart(): Unit = {
    super.preStart()
    synchronizer ! RegisterWatchdog(self)
  }

  override val receive: Receive = {
    // Manager messages
    case GetStatus(account) =>
      getStatus(account).pipeTo(sender())
    case GetStatuses =>
      getStatuses.pipeTo(sender())
    case RegisterAccount(wallet, account, accountInfo) =>
      registerAccount(wallet, account, accountInfo)
    case ForceSynchronization(accountInfo) =>
      forceSynchronization(accountInfo).pipeTo(sender())
    case ForceAllSynchronizations =>
      forceAllSynchronizations.pipeTo(sender())
    case UnregisterPool(poolName) =>
      unregisterPool(poolName).pipeTo(sender())
    case Resync(accountInfo) =>
      resyncAccount(accountInfo)
    // AccountSynchronizer messages
    case SyncStarted(accountInfo) =>
      handleSyncStarted(accountInfo)
    case SyncSuccess(accountInfo) =>
      handleSyncSuccess(accountInfo)
    case SyncFailure(accountInfo, reason) =>
      handleSyncFailure(accountInfo, reason)
  }

  private def resyncAccount(accountInfo: AccountInfo): Unit = {
    val accountData = accounts(accountInfo)

    wipeAllOperations(accountData.account, accountInfo).andThen { case _ =>
      synchronizer ! StartSynchronization(accountData.account, accountInfo)
    }
  }

  private def wipeAllOperations(account: Account, accountInfo: AccountInfo) = for {
    lastKnownOperationsCount <- account.operationCounts.map(_.values.sum)
    _ = log.info(s"#Sync : erased all the operations of account $accountInfo, last known op count $lastKnownOperationsCount")
    errorCode <- account.eraseDataSince(new Date(0))
  } yield {
    log.info(s"#Sync : account $accountInfo libcore wipe ended (errorCode : $errorCode ")
  }

  private def unregisterPool(poolName: String) = {
    accounts.keysIterator
      .filter(_.poolName == poolName)
      .foreach(accountInfo => {
        accounts.remove(accountInfo)
        synchronizer ! ForceStopSynchronization(accountInfo)
      })
    Future.unit
  }

  private def forceAllSynchronizations = {
    // FIXME: this probably just OOM the process or steals all the CPU
    accounts.keysIterator.map(accountInfo => ongoingForceSyncs.get(accountInfo)
      .map(p => p.future)
      .getOrElse(forceSynchronization(accountInfo))
    ).toList.sequence
  }

  private def forceSynchronization(accountInfo: AccountInfo): Future[SyncStatus] = {
    accounts.get(accountInfo) match {
      case Some(accountData) => ongoingForceSyncs.get(accountInfo) match {
        case None =>
          synchronizer ! ForceStartSynchronization(accountData.account, accountInfo)
          val promisedSync = Promise[SyncStatus]()
          ongoingForceSyncs += (accountInfo -> promisedSync)
          promisedSync.future
        case Some(_) =>
          Future.failed[SyncStatus](
            SyncOnGoingException()
          )
      }
      case None => log.error(s"Trying to force synchronization on un-registered account $accountInfo")
        Future.failed[SyncStatus](
          AccountNotFoundException(accountInfo.accountIndex)
        )
    }
  }

  private def handleSyncStarted(accountInfo: AccountInfo): Unit = {
    val syncing = accounts(accountInfo).syncStatus match {
      case Synced(atHeight) => Syncing(atHeight, atHeight)
      case FailedToSync(_) => Syncing(0, 0)
      case other =>
        log.warning(s"Unexpected previous account synchronization state $other before starting new synchronization. Keep status as is, although synchronization is running.")
        other
    }
    log.debug(s"Updated new status of account $accountInfo: $syncing")
    accounts += ((accountInfo, accounts(accountInfo).copy(syncStatus = syncing)))
  }

  private def handleSyncFailure(accountInfo: AccountInfo, reason: String) = {
    val accountData = accounts(accountInfo)

    log.warning(s"Failed to sync account $accountInfo: $reason. Re-scheduled.")
    afterSyncEpilogue(accountInfo, accountData, FailedToSync(reason))
  }

  private def handleSyncSuccess(accountInfo: AccountInfo): Unit = {
    val accountData = accounts(accountInfo)

    lastAccountBlockHeight(accountData.account).andThen {
      case Success(blockHeight) =>
        afterSyncEpilogue(accountInfo, accountData, Synced(blockHeight.value))
      case scala.util.Failure(e) =>
        log.warning(s"Could not update status of account $accountInfo although the synchronization ended well. Still marked as fail.")
        afterSyncEpilogue(accountInfo, accountData, FailedToSync(e.getMessage))
    }
  }

  private def afterSyncEpilogue(accountInfo: AccountInfo, accountData: AccountData, newSyncStatus: SyncStatus) = {
    accounts += (accountInfo -> accountData.copy(syncStatus = newSyncStatus))
    ongoingForceSyncs.remove(accountInfo).foreach(p => {
      p.success(newSyncStatus)
    })
    accountData.publisher ! newSyncStatus
    log.debug(s"New status for account $accountInfo: ${accounts(accountInfo).syncStatus}")

    scheduler.doLater(Duration.fromSeconds(DaemonConfiguration.Synchronization.syncInterval)) {
      synchronizer ! StartSynchronization(accountData.account, accountInfo)
    }
  }

  private def registerAccount(wallet: Wallet, account: Account, accountInfo: AccountInfo): Unit = {
    if (!accounts.contains(accountInfo)) {
      val accountOperationsPublisher = createAccountPublisher(wallet, account, accountInfo)
      val accountData = AccountData(
        account = account,
        wallet = wallet,
        syncStatus = Synced(0),
        publisher = accountOperationsPublisher
      )
      accounts += ((accountInfo, accountData))
      synchronizer ! StartSynchronization(account, accountInfo)
    }
  }

  private def createAccountPublisher(wallet: Wallet, account: Account, accountInfo: AccountInfo) = {
    val accountOperationsPublisher = publisherFactory(this.context, cache, account, wallet, PoolName(accountInfo.poolName))
    accountsPublisherIndex += (accountOperationsPublisher -> accountInfo)
    accountOperationsPublisher
  }

  private def getStatuses: Future[List[(AccountInfo, SyncStatus)]] = {
    Future.sequence(accounts.map(e =>
      getStatusForExistingAccount(e._1)(e._2).map((e._1, _))
    ).toList)
  }

  private def getStatus(accountInfo: AccountInfo): Future[Option[SyncStatus]] = accounts.get(accountInfo).map {
    getStatusForExistingAccount(accountInfo)
  }.sequence

  private def getStatusForExistingAccount(accountInfo: AccountInfo)(accountData: AccountData) = {
    accountData.syncStatus match {
      case Syncing(fromHeight, _) => lastAccountBlockHeight(accountData.account).map(blockHeight => {
        val newStatus = Syncing(fromHeight, blockHeight.value)
        accounts += ((accountInfo, accountData.copy(syncStatus = newStatus)))
        newStatus
      })
      case otherStatus => Future.successful(otherStatus)
    }
  }

  private def lastAccountBlockHeight(account: Account): Future[BlockHeight] = {
    import co.ledger.wallet.daemon.context.ApplicationContext.IOPool

    account.getLastBlock()
      .map(h => BlockHeight(h.getHeight))(IOPool)
      .recover {
        case _: co.ledger.core.implicits.BlockNotFoundException => BlockHeight(0)
      }(IOPool)
  }

}

object AccountSynchronizerWatchdog {

  /**
    * Gives the status @SyncStatus of the synchronization of the account
    */
  case class GetStatus(account: AccountInfo)

  case object GetStatuses

  case object ForceAllSynchronizations

  case class ForceSynchronization(accountInfo: AccountInfo)

  case class RegisterAccount(wallet: Wallet, account: Account, accountInfo: AccountInfo)

  case class UnregisterPool(poolName: String)

  case class Resync(accountInfo: AccountInfo)

  private case class BlockHeight(value: Long) extends AnyVal

}

class AccountSynchronizer() extends Actor with ActorLogging {

  implicit val ec: ExecutionContext = context.dispatcher
  var onGoingSyncs: mutable.HashMap[AccountInfo, Future[SyncResult]] = new mutable.HashMap()
  val queue: mutable.Queue[(Account, AccountInfo)] = new mutable.Queue[(Account, AccountInfo)]

  var watchdog: ActorRef = ActorRef.noSender

  override def preStart(): Unit = {
    super.preStart()
    log.info("Registered Account Synchronizer")
  }

  override val receive: Receive = {
    // Watchdog messages
    case RegisterWatchdog(watchdog) =>
      log.info("Registered Watchdog in AccountSynchronizer")
      this.watchdog = watchdog
    case ForceStopSynchronization(accountInfo) =>
      forceStopSynchronization(accountInfo)
    case ForceStartSynchronization(account, accountInfo) =>
      startSync(account, accountInfo)
    case StartSynchronization(account, accountInfo) =>
      tryToSync(account, accountInfo)
    // Self messages
    case result: SyncResult =>
      watchdog ! result
      onGoingSyncs.remove(result.accountInfo)
      if (queue.nonEmpty) {
        val (account, accountInfo) = queue.dequeue()
        self ! StartSynchronization(account, accountInfo)
      }
  }

  def forceStopSynchronization(accountInfo: AccountInfo): Unit = {
    // The future API does not provide a cancel concept (unlike java), so we just remove the reference to the future
    // Maybe we could use: https://pdf.zlibcdn.com/dtoken/d4e0004fdeacbefd04a0021668c6103d/Learning_Concurrent_Programming_in_Scala_by_Prokop_3429063_(z-lib.org).pdf#page=158
    // But that does even guaranty that the underlying libcore thread will be effectively stopped
    onGoingSyncs.remove(accountInfo)
  }

  private def tryToSync(account: Account, accountInfo: AccountInfo) = {
    if (onGoingSyncs.size >= DaemonConfiguration.Synchronization.maxOnGoing) {
      queue += ((account, accountInfo))
    } else {
      startSync(account, accountInfo)
    }
  }

  private def startSync(account: Account, accountInfo: AccountInfo): Unit = {
    onGoingSyncs += (accountInfo -> sync(account, accountInfo).pipeTo(self))
    watchdog ! SyncStarted(accountInfo)
  }

  private def sync(account: Account, accountInfo: AccountInfo): Future[SyncResult] = {
    import co.ledger.wallet.daemon.context.ApplicationContext.IOPool
    val accountUrl: String = s"${accountInfo.poolName}/${accountInfo.walletName}/${account.getIndex}"

    log.info(s"[${self.path}]#Sync : start syncing $accountInfo")
    account.sync(accountInfo.poolName, accountInfo.walletName)(IOPool)
      .map { result =>
        if (result.syncResult) {
          log.info(s"#[${self.path}]Sync : $accountUrl has been synced : $result")
          SyncSuccess(accountInfo)
        } else {
          log.error(s"#Sync : $accountUrl has FAILED")
          SyncFailure(accountInfo, s"#Sync : Lib core failed to sync the account $accountUrl")
        }
      }(IOPool)
      .recoverWith { case NonFatal(t) =>
        log.error(t, s"#Sync Failed to sync account: $accountUrl")
        Future.successful(SyncFailure(accountInfo, t.getMessage))
      }(IOPool)
  }
}

class AccountSynchronizerMailbox(val settings: ActorSystem.Settings, val config: Config) extends UnboundedStablePriorityMailbox(
  // lower prio means more important
  PriorityGenerator {
    case ForceStopSynchronization(_) => 0
    case ForceStartSynchronization(_, _) => 1
    case PoisonPill => 3
    case _ => 2
  })

object AccountSynchronizer {

  final case class RegisterWatchdog(watchdog: ActorRef)

  sealed trait Synchronization {
    def account: Account

    def accountInfo: AccountInfo
  }

  final case class StartSynchronization(account: Account, accountInfo: AccountInfo) extends Synchronization

  final case class ForceStartSynchronization(account: Account, accountInfo: AccountInfo) extends Synchronization

  final case class ForceStopSynchronization(accountInfo: AccountInfo)

  case class SyncStarted(accountInfo: AccountInfo)

  sealed trait SyncResult {
    def accountInfo: AccountInfo
  }

  case class SyncSuccess(accountInfo: AccountInfo) extends SyncResult

  case class SyncFailure(accountInfo: AccountInfo, reason: String) extends SyncResult

  def name(account: Account, wallet: Wallet, poolName: PoolName): String = AkkaUtils.validActorName("account-synchronizer", poolName.name, wallet.getName, account.getIndex.toString)
}


sealed trait SynchronizationDispatcher

object SynchronizationDispatcher {

  object LibcoreLookup extends SynchronizationDispatcher

  object Synchronizer extends SynchronizationDispatcher

  object Publisher extends SynchronizationDispatcher

  type DispatcherKey = String

  def configurationKey(dispatcher: SynchronizationDispatcher): DispatcherKey = dispatcher match {
    case LibcoreLookup => "synchronization.libcore-lookup.dispatcher"
    case Synchronizer => "synchronization.synchronizer.dispatcher"
    case Publisher => "synchronization.publisher.dispatcher"
  }

}


