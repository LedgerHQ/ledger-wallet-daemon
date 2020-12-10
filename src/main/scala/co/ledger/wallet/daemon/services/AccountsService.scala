package co.ledger.wallet.daemon.services

import java.net.URL
import java.util.{Date, UUID}

import cats.data.OptionT
import cats.instances.future._
import cats.instances.list._
import cats.syntax.either._
import cats.syntax.traverse._
import co.ledger.core
import co.ledger.core.{Account, Wallet}
import co.ledger.wallet.daemon.clients.{ClientFactory, ScalaHttpClientPool}
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.context.ApplicationContext.IOPool
import co.ledger.wallet.daemon.database.DaemonCache
import co.ledger.wallet.daemon.exceptions.{AccountNotFoundException, ERC20NotFoundException, FallbackBalanceProviderException, ResyncOnGoingException}
import co.ledger.wallet.daemon.models.Account._
import co.ledger.wallet.daemon.models.Currency._
import co.ledger.wallet.daemon.models.Operations.{OperationView, PackedOperationsView}
import co.ledger.wallet.daemon.models.Wallet._
import co.ledger.wallet.daemon.models._
import co.ledger.wallet.daemon.modules.PublisherModule.PoolPublisher
import co.ledger.wallet.daemon.schedulers.observers.SynchronizationResult
import co.ledger.wallet.daemon.services.AccountOperationsPublisher.PoolName
import co.ledger.wallet.daemon.utils.Utils
import co.ledger.wallet.daemon.utils.Utils.{RichBigInt, _}
import com.google.common.cache.{CacheBuilder, CacheLoader}
import javax.inject.{Inject, Singleton}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

@Singleton
class AccountsService @Inject()(daemonCache: DaemonCache, synchronizerManager: AccountSynchronizerManager, poolPublisher: PoolPublisher) extends DaemonService {

  case class CacheKey(a: AccountInfo, contract: Option[String])

  // Caching Future result of getBalance in order to share same Future to every requests
  private val balanceCache =
    CacheBuilder.newBuilder()
      .maximumSize(DaemonConfiguration.balanceCacheMaxSize)
      .expireAfterWrite(java.time.Duration.ofMinutes(DaemonConfiguration.balanceCacheTtlMin))
      .build[CacheKey, Future[BigInt]](new CacheLoader[CacheKey, Future[BigInt]] {
        def load(key: CacheKey): Future[BigInt] = {
          loadBalance(key.contract, key.a)
        }
      })

  private def accountView(pool: Pool, walletInfo: WalletInfo, wallet: Wallet)(account: Account): Future[Option[AccountView]] = {
    val status: Future[Option[SyncStatus]] = synchronizerManager.getSyncStatus(AccountInfo(account.getIndex, walletInfo))
    val view: SyncStatus => Future[AccountView] = account.accountView(pool, wallet, wallet.getCurrency.currencyView, _)
    OptionT(status).semiflatMap(view).value
  }

  def accounts(walletInfo: WalletInfo): Future[Seq[AccountView]] = {
    daemonCache.withWalletAndPool(walletInfo) { (w, p) =>
      w.accounts.flatMap { as =>
        as.map(accountView(p, walletInfo, w)(_)).toList.sequence[Future, Option[AccountView]].map(_.flatten)
      }
    }
  }

  def account(accountInfo: AccountInfo): Future[Option[AccountView]] = {
    daemonCache.withWalletAndPool(accountInfo.walletInfo) { (wallet, pool) =>
      (for {
        a <- OptionT(wallet.account(accountInfo.accountIndex))
        view <- OptionT(accountView(pool, accountInfo.walletInfo, wallet)(a))
      } yield view).value
    }
  }

  /**
    * This method will wipe all the operations in the account, and resynchronize them from the
    * explorer. During the resynchronization, the account will not be accessible.
    */
  def resynchronizeAccount(accountInfo: AccountInfo): Unit = {
    synchronizerManager.resyncAccount(accountInfo)
  }

  def syncStatus(accountInfo: AccountInfo): Future[Option[SyncStatus]] = {
    synchronizerManager.getSyncStatus(accountInfo)
  }

  def ongoingSyncs(): Future[List[(AccountInfo, SyncStatus)]] = {
    synchronizerManager.ongoingSyncs()
  }

  /**
    * Method to synchronize account operations from public resources. The method may take a while
    * to finish. This method only synchronize a single account.
    *
    * @return a Future of sequence of result of synchronization.
    */
  def synchronizeAccount(accountInfo: AccountInfo): Future[Seq[SynchronizationResult]] = synchronizerManager.syncAccount(accountInfo).map(Seq(_))

  def getAccount(accountInfo: AccountInfo): Future[Option[core.Account]] = daemonCache.getAccount(accountInfo: AccountInfo)

  def getBalance(contract: Option[String], accountInfo: AccountInfo): Future[BigInt] = checkSyncStatus(accountInfo) {
    balanceCache.get(CacheKey(accountInfo, contract))
  }

  def getUtxo(accountInfo: AccountInfo, offset: Int, batch: Int): Future[(List[UTXOView], Int)] = checkSyncStatus(accountInfo) {

    daemonCache.withAccountAndWallet(accountInfo) { (account, wallet) =>
      for {
        lastBlockHeight <- wallet.lastBlockHeight
        count <- account.getUtxoCount
        utxos <- account.getUtxo(lastBlockHeight, offset, batch)
      } yield (utxos, count)
    }
  }

  private def loadBalance(contract: Option[String], accountInfo: AccountInfo): Future[BigInt] = checkSyncStatus(accountInfo) {

    def runWithFallback(cb: Future[BigInt], url: URL, service: ScalaHttpClientPool, timeout: scala.concurrent.duration.Duration): Future[BigInt] = {
      Future(Await.result(cb, timeout)).recoverWith { case t =>
        warn(s"Using fallback provider $url due to Failed to get balance from libcore: $t")
        for {
          address <- accountFreshAddresses(accountInfo).map(_.head.address)
          balance <- FallbackService.getBalance(contract, address, service, url)
            .getOrElseF(Future.failed(FallbackBalanceProviderException(accountInfo.walletInfo.walletName, url.getHost, url.getPath)))
        } yield balance
      }
    }

    val balance = {
      daemonCache.withAccount(accountInfo)(a => {
        contract match {
          case Some(c) => a.erc20Balance(c).recoverWith({
            case _: ERC20NotFoundException => Future.successful(scala.BigInt(0))
          })
          case None => a.balance
        }
      })
    }

    daemonCache.withWallet(accountInfo.walletInfo) { wallet =>
      ClientFactory.apiClient.fallbackService(wallet.getCurrency.getName) match {
        case Some((url, service)) =>
          runWithFallback(balance, url, service, DaemonConfiguration.explorer.api.fallbackTimeout.milliseconds)
        case None =>
          balance
      }
    }
  }

  def getXpub(accountInfo: AccountInfo): Future[String] = checkSyncStatus(accountInfo) {
    daemonCache.withAccount(accountInfo) { a => Future.successful(a.getRestoreKey) }
  }

  def getERC20Operations(accountInfo: AccountInfo): Future[List[OperationView]] = checkSyncStatus(accountInfo) {
    daemonCache.withAccountAndWallet(accountInfo) {
      case (account, wallet) => account.erc20Operations(wallet)
    }
  }

  def getBatchedERC20Operations(tokenAccountInfo: TokenAccountInfo, offset: Int, batch: Int): Future[List[OperationView]] = checkSyncStatus(tokenAccountInfo.accountInfo) {

    daemonCache.withAccountAndWallet(tokenAccountInfo.accountInfo) {
      case (account, wallet) =>
        account.batchedErc20Operations(wallet, tokenAccountInfo.tokenAddress, offset, batch)
          .recoverWith({
            case _: ERC20NotFoundException => Future.successful(List.empty)
          })
    }
  }

  def getBatchedERC20Operations(accountInfo: AccountInfo, offset: Int, batch: Int): Future[List[OperationView]] = checkSyncStatus(accountInfo) {

    daemonCache.withAccountAndWallet(accountInfo) {
      case (account, wallet) =>
        account.batchedErc20Operations(wallet, offset, batch)
    }
  }

  def getTokenAccounts(accountInfo: AccountInfo): Future[List[ERC20AccountView]] = checkSyncStatus(accountInfo) {

    daemonCache.withAccount(accountInfo) { account =>
      for {
        erc20Accounts <- account.erc20Accounts.liftTo[Future]
        erc20Balances <- account.erc20Balances(Some(erc20Accounts.map(acc => acc.getToken.getContractAddress).toArray))
        views <- erc20Accounts.zip(erc20Balances).map(v => ERC20AccountView.fromERC20Account(v._1, v._2)).sequence
      } yield views
    }
  }

  def getTokenAccount(tokenAccountInfo: TokenAccountInfo): Future[ERC20AccountView] = checkSyncStatus(tokenAccountInfo.accountInfo) {

    daemonCache.withAccount(tokenAccountInfo.accountInfo) { account =>
      for {
        erc20Account <- account.erc20Account(tokenAccountInfo.tokenAddress).liftTo[Future]
        balance <- account.erc20Balances(Some(Array[String](tokenAccountInfo.tokenAddress)))
        view <- ERC20AccountView.fromERC20Account(erc20Account, balance.head)
      } yield view
    }
  }

  def getTokenCoreAccount(tokenAccountInfo: TokenAccountInfo): Future[core.ERC20LikeAccount] = checkSyncStatus(tokenAccountInfo.accountInfo) {

    daemonCache.withAccount(tokenAccountInfo.accountInfo)(_.erc20Account(tokenAccountInfo.tokenAddress).liftTo[Future])
  }

  def getTokenCoreAccountBalanceHistory(tokenAccountInfo: TokenAccountInfo, startDate: Date, endDate: Date, period: core.TimePeriod): Future[List[BigInt]] = checkSyncStatus(tokenAccountInfo.accountInfo) {

    getTokenCoreAccount(tokenAccountInfo).map(_.getBalanceHistoryFor(startDate, endDate, period).asScala.map(_.asScala).toList)
      .recoverWith({
        case _: ERC20NotFoundException => Future.successful(List.fill(Utils.intervalSize(startDate, endDate, period))(BigInt(0)))
      })
  }

  def accountFreshAddresses(accountInfo: AccountInfo): Future[Seq[FreshAddressView]] = daemonCache.getFreshAddresses(accountInfo)

  def accountAddressesInRange(from: Long, to: Long, accountInfo: AccountInfo): Future[Seq[FreshAddressView]] = daemonCache.getAddressesInRange(from, to, accountInfo)

  def accountDerivationPath(accountInfo: AccountInfo): Future[String] =
    daemonCache.withWallet(accountInfo.walletInfo)(_.accountDerivationPathInfo(accountInfo.accountIndex))

  def nextAccountCreationInfo(accountIndex: Option[Int], walletInfo: WalletInfo): Future[AccountDerivationView] =
    daemonCache.withWallet(walletInfo)(_.accountCreationInfo(accountIndex)).map(_.view)

  def nextExtendedAccountCreationInfo(accountIndex: Option[Int], walletInfo: WalletInfo): Future[AccountExtendedDerivationView] =
    daemonCache.withWallet(walletInfo)(_.accountExtendedCreation(accountIndex)).map(_.view)

  def accountOperations(queryParams: OperationQueryParams, accountInfo: AccountInfo): Future[PackedOperationsView] = checkSyncStatus(accountInfo) {

    (queryParams.next, queryParams.previous) match {
      case (Some(n), _) =>
        // next has more priority, using database batch instead queryParams.batch
        info(LogMsgMaker.newInstance(s"Retrieve next batch operation for $accountInfo").toString())
        daemonCache.getNextBatchAccountOperations(n, queryParams.fullOp, accountInfo)
      case (_, Some(p)) =>
        info(LogMsgMaker.newInstance(s"Retrieve previous operations $accountInfo").toString())
        daemonCache.getPreviousBatchAccountOperations(p, queryParams.fullOp, accountInfo)
      case _ =>
        // new request
        info(LogMsgMaker.newInstance(s"Retrieve latest operations $accountInfo").toString())
        daemonCache.getAccountOperations(queryParams.batch, queryParams.fullOp, accountInfo)
    }
  }

  def firstOperation(accountInfo: AccountInfo): Future[Option[OperationView]] = checkSyncStatus(accountInfo) {
    daemonCache.withAccountAndWallet(accountInfo) {
      case (account, wallet) =>
        account.firstOperationView(wallet)
    }
  }

  def latestOperations(accountInfo: AccountInfo, latests: Int): Future[Seq[OperationView]] = {
    daemonCache.withAccountAndWallet(accountInfo) {
      case (account, wallet) => account.latestOperationViews(latests, wallet)
    }
  }

  def accountOperation(uid: String, fullOp: Int, accountInfo: AccountInfo): Future[Option[OperationView]] = checkSyncStatus(accountInfo) {
    daemonCache.withAccountAndWallet(accountInfo) {
      case (account, wallet) =>
        account.operationView(uid, fullOp, wallet)
    }
  }

  def createAccount(accountCreationBody: AccountDerivationView, walletInfo: WalletInfo): Future[AccountView] =
    daemonCache.withWalletAndPool(walletInfo) {
      (w, p) =>
        w.addAccountIfNotExist(accountCreationBody).flatMap { a =>
          val accountInfo = AccountInfo(a.getIndex, walletInfo)
          synchronizerManager.registerAccount(w, a, accountInfo)
          accountView(p, walletInfo, w)(a).map(_.get)
        }
    }

  def createAccountWithExtendedInfo(derivations: AccountExtendedDerivationView, walletInfo: WalletInfo): Future[AccountView] =
    daemonCache.withWalletAndPool(walletInfo) {
      (w, p) =>
        w.addAccountIfNotExist(derivations).flatMap { a =>
          val accountInfo = AccountInfo(a.getIndex, walletInfo)
          synchronizerManager.registerAccount(w, a, accountInfo)
          accountView(p, walletInfo, w)(a).map(_.get)
        }
    }

  def repushOperations(accountInfo: AccountInfo, fromHeight: Option[Long]): Future[Unit] = {

    val publisher = poolPublisher(PoolName(accountInfo.poolName))

    daemonCache.withAccountAndWallet(accountInfo) {
      case (account, wallet) =>
        val pushOperationFuture = account.operationViewsFromHeight(0, Int.MaxValue, 1, fromHeight.getOrElse(0), wallet)
          .map(_.map(view =>
            publisher.publishOperation(view, account, wallet, accountInfo.poolName)
          ))
        val pushErc20Future: Future[Unit] = if (account.isInstanceOfEthereumLikeAccount) {
          daemonCache.withAccountAndWalletAndPool(accountInfo) { (account, wallet, pool) =>
            account.erc20OperationsFromHeight(pool, wallet, account, offset = 0, batch = Int.MaxValue, fromHeight.getOrElse(0)).map(_.map(view =>
              publisher.publishERC20Operation(view, account, wallet, accountInfo.poolName)
            ))
          }
        } else Future.unit
        pushOperationFuture.flatMap(_ => pushErc20Future)
    }
  }

  private def checkSyncStatus[T](account: AccountInfo)(block: => Future[T]): Future[T] = {
    val status = synchronizerManager.getSyncStatus(account)
    status.flatMap {
      case Some(Resyncing(targetHeight, currentHeight)) => Future.failed(ResyncOnGoingException(targetHeight, currentHeight))
      case None => Future.failed(AccountNotFoundException(account.accountIndex))
      case _ => Future.successful(())
    }.flatMap(_ => block)
  }

  def getDelegations(accountInfo: AccountInfo): Future[Seq[DelegationView]] = {
    daemonCache.withAccountAndWallet(accountInfo) {
      case (account, _) => account.getDelegations()
    }
  }
}

case class OperationQueryParams(previous: Option[UUID], next: Option[UUID], batch: Int, fullOp: Int)
