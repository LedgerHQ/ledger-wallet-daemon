package co.ledger.wallet.daemon.database.core

import co.ledger.core.{Account, Wallet, WalletType}
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.database.core.operations._
import co.ledger.wallet.daemon.models.Operations.OperationView
import com.twitter.inject.Logging
import com.twitter.util.Future

import scala.concurrent.ExecutionContext

class WalletPoolDao(poolName: String)(implicit val ec: ExecutionContext) extends CoinDao with ERC20Dao with Logging {
  val db: Database = new Database(DaemonConfiguration.coreDbConfig, poolName)

  lazy val btcDao = new BitcoinDao(db)
  lazy val ethDao = new EthereumDao(db, poolName)
  lazy val xrpDao = new RippleDao(db)
  lazy val xlmDao = new StellarDao(db)
  lazy val xtzDao = new TezosDao(db)

  /**
    * List operations from an account
    */
  override def listAllOperations(a: Account, w: Wallet, offset: Int, limit: Int): Future[Seq[OperationView]] =
    daoForWalletType(w.getWalletType).listAllOperations(a, w, offset, limit)

  /**
    * Find Operation
    */
  override def findOperationByUid(a: Account, w: Wallet, uid: OperationUid, offset: Int, limit: Int): Future[Option[OperationView]] =
    daoForWalletType(w.getWalletType).findOperationByUid(a, w, uid, offset, limit)

  /**
    * List operations from an account filtered by Uids
    */
  override def findOperationsByUids(a: Account, w: Wallet, filteredUids: Seq[OperationUid], offset: Int, limit: Int): Future[Seq[OperationView]] =
    daoForWalletType(w.getWalletType).findOperationsByUids(a, w, filteredUids, offset, limit)

  private def daoForWalletType(wt: WalletType): CoinDao = wt match {
    case WalletType.BITCOIN => btcDao
    case WalletType.ETHEREUM => ethDao
    case WalletType.RIPPLE => xrpDao
    case WalletType.STELLAR => xlmDao
    case WalletType.TEZOS => xtzDao
    case _ => throw new NotImplementedError(s"Dao not implemented for $wt")
  }

  private def erc20daoForWalletType(wt: WalletType): ERC20Dao = wt match {
    case WalletType.ETHEREUM => ethDao
    case _ => throw new NotImplementedError(s"This wallet is not ERC20 compatible $wt")
  }

  /**
    * List operations erc20 from an erc20 account
    */
  override def listAllERC20Operations(a: Account, w: Wallet, erc20AccountUid: ERC20AccountUid, offset: Int, limit: Int): Future[Seq[OperationView]] =
    erc20daoForWalletType(w.getWalletType: WalletType).listAllERC20Operations(a, w, erc20AccountUid, offset, limit)

  /**
    * Find Erc20 Operation by Uid
    */
  override def findERC20OperationByUid(a: Account, w: Wallet, uid: ERC20OperationUid): Future[Option[OperationView]] =
    erc20daoForWalletType(w.getWalletType: WalletType).findERC20OperationByUid(a, w, uid)


  /**
    * List erc20 operations from an account filtered by Uids
    */
  override def findERC20OperationsByUids(a: Account, w: Wallet, filteredUids: Seq[ERC20OperationUid], offset: Int, limit: Int): Future[Seq[OperationView]] =
    erc20daoForWalletType(w.getWalletType: WalletType).findERC20OperationsByUids(a, w, filteredUids, offset, limit)

  /**
    * List erc20 operations from an account starting at specified block height
    */
  override def findERC20OperationsFromBlockHeight(a: Account, w: Wallet, blockHeight: Long, offset: Int, limit: Int): Future[Seq[OperationView]] =
    erc20daoForWalletType(w.getWalletType: WalletType).findERC20OperationsFromBlockHeight(a, w, blockHeight, offset, limit)

}

object WalletPoolDao {
  def apply(name: String)(implicit ec: ExecutionContext): WalletPoolDao = new WalletPoolDao(name)
}
