package co.ledger.wallet.daemon.services

import co.ledger.wallet.daemon.context.ApplicationContext.IOPool
import co.ledger.wallet.daemon.database.DaemonCache
import co.ledger.wallet.daemon.exceptions.WalletPoolNotFoundException
import co.ledger.wallet.daemon.models.{PoolInfo, WalletPoolView}
import co.ledger.wallet.daemon.schedulers.observers.SynchronizationResult
import javax.inject.{Inject, Singleton}

import scala.concurrent.Future

@Singleton
class PoolsService @Inject()(daemonCache: DaemonCache, accountSynchronizer: AccountSynchronizerManager) extends DaemonService {

  import PoolsService._

  def createPool(poolInfo: PoolInfo, configuration: PoolConfiguration): Future[WalletPoolView] = {
    daemonCache.createWalletPool(poolInfo, configuration.toString).flatMap(_.view)
  }

  def pools(): Future[Seq[WalletPoolView]] = {
    daemonCache.getAllPools.map(pools => Future.sequence(pools.map(_.view))).flatten
  }

  def pool(poolInfo: PoolInfo): Future[Option[WalletPoolView]] = {
    daemonCache.getWalletPool(poolInfo).flatMap {
      case Some(pool) => pool.view.map(Option(_))
      case None => Future(None)
    }
  }

  def removePool(poolInfo: PoolInfo): Future[Unit] = {
    daemonCache.getWalletPool(poolInfo).flatMap(pool => pool.fold(
      Future.failed[Unit](WalletPoolNotFoundException(poolInfo.poolName))
    )(_ => accountSynchronizer.unregisterPool(poolInfo)
    )).flatMap(_ => daemonCache.deleteWalletPool(poolInfo))
      .map(_ => info(s"Pool $poolInfo has been deleted"))
  }

  def syncOperations: Future[Seq[SynchronizationResult]] = accountSynchronizer.syncAllRegisteredAccounts()

  /**
    * Method to synchronize account operations from public resources. The method may take a while
    * to finish. This method only synchronize a single pool.
    *
    * @return a Future of sequence of result of synchronization.
    */
  def syncOperations(poolInfo: PoolInfo): Future[Seq[SynchronizationResult]] =
    accountSynchronizer.syncPool(poolInfo)

}

object PoolsService {

  case class PoolConfiguration() {
    override def toString: String = ""
  }

}
