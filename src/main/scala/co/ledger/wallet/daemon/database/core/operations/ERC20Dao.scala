package co.ledger.wallet.daemon.database.core.operations

import co.ledger.core.{Account, Wallet}
import co.ledger.wallet.daemon.models.Operations.OperationView
import com.twitter.util.Future

trait ERC20Dao {

  type ERC20OperationUid = String
  type ERC20AccountUid = String

  /**
    * List operations erc20 from an erc20 account
    */
  def listAllERC20Operations(a: Account, w: Wallet, erc20AccountUid: ERC20AccountUid, offset: Int, limit: Int): Future[Seq[OperationView]]

  /**
    * Find Erc20 Operation by Uid
    */
  def findERC20OperationByUid(a: Account, w: Wallet, uid: ERC20OperationUid): Future[Option[OperationView]]

  /**
    * List erc20 operations from an account filtered by Uids
    */
  def findERC20OperationsByUids(a: Account, w: Wallet, filteredUids: Seq[ERC20OperationUid], offset: Int, limit: Int): Future[Seq[OperationView]]

  /**
    * List erc20 operations from an account starting at specified block height
    */
  def findERC20OperationsFromBlockHeight(a: Account, w: Wallet, blockHeight: Long, offset: Int, limit: Int): Future[Seq[OperationView]]
}
