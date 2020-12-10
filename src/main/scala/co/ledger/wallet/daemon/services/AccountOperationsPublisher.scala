package co.ledger.wallet.daemon.services


import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.LoggingReceive
import cats.data.OptionT
import cats.implicits._
import co.ledger.core._
import co.ledger.wallet.daemon.database.DaemonCache
import co.ledger.wallet.daemon.exceptions.InvalidErc20OperationsListException
import co.ledger.wallet.daemon.libledger_core.async.LedgerCoreExecutionContext
import co.ledger.wallet.daemon.models.Operations.OperationView
import co.ledger.wallet.daemon.models.{Pool, PoolInfo}
import co.ledger.wallet.daemon.services.AccountOperationsPublisher._
import co.ledger.wallet.daemon.utils.AkkaUtils
import co.ledger.wallet.daemon.utils.Utils._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future => ScalaFuture}

class AccountOperationsPublisher(daemonCache: DaemonCache, account: Account, wallet: Wallet, poolName: PoolName, publisher: Publisher) extends Actor with ActorLogging {

  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher
  val lookupDispatcher: ExecutionContextExecutor = context.system.dispatchers.lookup(SynchronizationDispatcher.configurationKey(SynchronizationDispatcher.LibcoreLookup))

  private lazy val pool: Pool = Await.result(daemonCache.getWalletPool(PoolInfo(poolName.name))(lookupDispatcher), 30.seconds).get
  private lazy val walletPoolDao = pool.walletPoolDao

  private val eventReceiver = new AccountOperationReceiver(self)
  private val accountInfo: String = s"$poolName/${wallet.getName}/${account.getIndex}"

  override def preStart(): Unit = {
    super.preStart()
    log.info(s"Actor $accountInfo is starting, Actor name=${self.path.name}")
    listenOperationsEvents(account)
  }

  override def postStop(): Unit = {
    log.info(s"Actor $accountInfo is stopping, Actor name=${self.path.name}")
    stopListeningEvents(account)
    super.postStop()
  }

  override def receive: Receive = LoggingReceive {
    case s: SyncStatus if account.isInstanceOfEthereumLikeAccount =>
      publisher.publishAccount(pool, account, wallet, s).flatMap(_ => {
        publisher.publishERC20Accounts(pool, account, wallet, s)
      })
    case s: SyncStatus =>
      publisher.publishAccount(pool, account, wallet, s)
    case NewERC20OperationEvent(_, opId) =>
      fetchErc20OperationView(opId).fold(log.warning(s"operation not found: $opId"))(op => publisher.publishERC20Operation(op, account, wallet, poolName.name))
    case UpdatedERC20OperationsEvent(ops) =>
      fetchErc20OperationsViews(ops).foreach(_.foreach(publisher.publishERC20Operation(_, account, wallet, poolName.name)))
    case UpdatedOperationsEvent(opIds) =>
      fetchOperationsViews(opIds).foreach(_.foreach(publisher.publishOperation(_, account, wallet, poolName.name)))
    case DeletedOperationEvent(opId) =>
      publisher.publishDeletedOperation(opId.uid, account, wallet, poolName.name)
  }

  private def listenOperationsEvents(account: Account): Unit = eventReceiver.listenEvents(account.getEventBus)

  private def stopListeningEvents(account: Account): Unit = eventReceiver.stopListeningEvents(account.getEventBus)

  private def fetchOperationsViews(ids: Seq[OperationId]): ScalaFuture[Seq[OperationView]] =
    walletPoolDao.findOperationsByUids(account, wallet, ids.map(_.uid), 0, Int.MaxValue).asScala()

  private def fetchErc20OperationView(erc20op: OperationId): OptionT[ScalaFuture, OperationView] =
    OptionT(walletPoolDao.findERC20OperationByUid(account, wallet, erc20op.uid).asScala())

  private def fetchErc20OperationsViews(erc20ops: List[(Erc20AccountUid, OperationId)]): ScalaFuture[Seq[OperationView]] =
    walletPoolDao.findERC20OperationsByUids(account, wallet, erc20ops.map(_._2.uid), 0, Int.MaxValue).asScala()
}

object AccountOperationsPublisher {

  case class PoolName(name: String) extends AnyVal

  case class OperationId(uid: String) extends AnyVal

  case class Erc20AccountUid(uid: String) extends AnyVal

  case class UpdatedOperationsEvent(uids: List[OperationId])

  case class UpdatedERC20OperationsEvent(operations: List[(Erc20AccountUid, OperationId)])

  case class NewERC20OperationEvent(accUid: Erc20AccountUid, uid: OperationId)

  case class DeletedOperationEvent(uid: OperationId)

  def props(cache: DaemonCache, account: Account, wallet: Wallet, poolName: PoolName, publisher: Publisher): Props =
    Props(new AccountOperationsPublisher(cache, account, wallet, poolName, publisher))

  def name(account: Account, wallet: Wallet, poolName: PoolName): String = AkkaUtils.validActorName("operation-publisher", poolName.name, wallet.getName, account.getIndex.toString)

}

class AccountOperationReceiver(eventTarget: ActorRef) extends EventReceiver {

  override def onEvent(event: Event): Unit = event.getCode match {

    case EventCode.NEW_ERC20_OPERATION =>
      val uid = event.getPayload.getString(Account.EV_NEW_OP_UID)
      val accountUid = event.getPayload.getString(ERC20LikeAccount.EV_NEW_OP_ERC20_ACCOUNT_UID)
      eventTarget ! NewERC20OperationEvent(Erc20AccountUid(accountUid), OperationId(uid))

    case EventCode.UPDATE_OPERATIONS =>
      val rawUids = event.getPayload.getArray(Account.EV_NEW_OP_UID)

      val size = rawUids.size()
      val uids = (0L to size).map(rawUids.getString).map(OperationId).toList
      eventTarget ! UpdatedOperationsEvent(uids)

    case EventCode.UPDATE_ERC20_OPERATIONS =>
      val rawUids = event.getPayload.getArray(Account.EV_NEW_OP_UID)

      val size = rawUids.size()
      val accUids = (0L to size).map(rawUids.getString).map(Erc20AccountUid).toList
      val uids = (0L to size).map(rawUids.getString).map(OperationId).toList
      val defaultAccountUid = Erc20AccountUid("MissingAccountId")
      val defaultOperationId = OperationId("MissingOpID")
      val operations = accUids.zipAll(uids, defaultAccountUid, defaultOperationId)
      if (!operations.forall { case (accId, opId) => accId != defaultAccountUid && opId != defaultOperationId }) {
        throw InvalidErc20OperationsListException()
      }
      eventTarget ! UpdatedERC20OperationsEvent(operations)

    case EventCode.DELETED_OPERATION =>
      val uid = event.getPayload.getString(Account.EV_DELETED_OP_UID)
      eventTarget ! DeletedOperationEvent(OperationId(uid))

    case _ =>
  }

  def listenEvents(bus: EventBus)(implicit ec: ExecutionContext): Unit = bus.subscribe(LedgerCoreExecutionContext(ec), this)

  def stopListeningEvents(bus: EventBus): Unit = bus.unsubscribe(this)
}

