package co.ledger.wallet.daemon.database.core.operations

import java.util.Date

import co.ledger.core.{Account, Address, Currency, OperationType, Wallet}
import co.ledger.wallet.daemon.database.core.Database.SQLQuery
import co.ledger.wallet.daemon.database.core.Decoder._
import co.ledger.wallet.daemon.database.core.{Database, Ordering, PartialEthOperation}
import co.ledger.wallet.daemon.models.Operations.OperationView
import co.ledger.wallet.daemon.models.coins.Bitcoin.currencyFamily
import co.ledger.wallet.daemon.models.coins.EthereumTransactionView.ERC20
import co.ledger.wallet.daemon.models.coins.{CommonBlockView, EthereumTransactionView}
import com.twitter.finagle.postgres.Row
import com.twitter.inject.Logging
import com.twitter.util.Future

class EthereumDao(protected val db: Database, protected val poolName: String) extends CoinDao with ERC20Dao with Logging {
  logger.info(s"EthereumDao created for ${db.client}")

  private val ethOperationQuery: (Int, String, Ordering.OperationOrder, Option[Seq[OperationUid]], Int, Int) => SQLQuery =
    (accountIndex: Int, walletName: String, order: Ordering.OperationOrder, filteredUids: Option[Seq[OperationUid]], offset: Int, limit: Int) =>
      "SELECT o.uid, o.date, eop.transaction_hash, b.height as block_height, b.time as block_time, b.hash as block_hash, " +
        "o.type, o.amount, o.fees, o.senders, o.recipients, etx.gas_price, etx.gas_limit, etx.gas_used, etx.status, etx.confirmations " +
        "FROM accounts a, wallets w, operations o, ethereum_operations eop, ethereum_transactions etx, blocks b " +
        s"WHERE w.name='$walletName' AND a.idx='$accountIndex' " +
        "AND a.wallet_uid=w.uid AND o.account_uid=a.uid AND o.uid = eop.uid AND o.block_uid=b.uid AND etx.transaction_uid = eop.transaction_uid " +
        filteredUids.fold("")(uids => s"AND o.uid IN ('${uids.mkString("','")}') ") +
        "ORDER BY o.date " + order.value +
        s" OFFSET $offset LIMIT $limit"

  private val erc20OperationByEthUidsQuery: (Int, String, Ordering.OperationOrder, Option[Seq[OperationUid]], Int, Int) => SQLQuery =
    (accountIndex: Int, walletName: String, order: Ordering.OperationOrder, filteredUids: Option[Seq[OperationUid]], offset: Int, limit: Int) =>
      "SELECT ercop.uid as erc_uid, ercop.ethereum_operation_uid as eth_uid, ercop.receiver, ercop.value " +
        "FROM wallets w, erc20_operations ercop, erc20_accounts ercacc, ethereum_accounts ethacc " +
        s"WHERE w.name='$walletName' AND ethacc.idx='$accountIndex' " +
        "AND ethacc.wallet_uid = w.uid AND ercop.account_uid = ercacc.uid AND ercacc.ethereum_account_uid = ethacc.uid " +
        filteredUids.fold("")(uids => s"AND ercop.ethereum_operation_uid IN ('${uids.mkString("','")}') ") +
        "ORDER BY ercop.date " + order.value +
        s" OFFSET $offset LIMIT $limit"

  private val erc20OperationFromBlockHeightQuery: (Int, String, Long, Int, Int) => SQLQuery =
    (accountIndex: Int, walletName: String, blockHeight: Long, offset: Int, limit: Int) =>
      s"""
        |SELECT ercop.uid as erc_uid, ercop.ethereum_operation_uid as eth_uid, ercop.receiver, ercop.value
        | FROM wallets w, erc20_operations ercop, erc20_accounts ercacc, ethereum_accounts ethacc
        | WHERE w.name='$walletName' AND ethacc.idx='$accountIndex'
        | AND ethacc.wallet_uid = w.uid AND ercop.account_uid = ercacc.uid AND ercacc.ethereum_account_uid = ethacc.uid
        | AND ercop.block_height >= '$blockHeight'
        | ORDER BY ercop.date " + order.value
        | OFFSET $offset LIMIT $limit
        | """.stripMargin.replaceAll("\n", " ")

  private val ethOperationByErc20Uids: (Int, String, Ordering.OperationOrder, Option[Seq[ERC20OperationUid]], Int, Int) => SQLQuery =
    (accountIndex: Int, walletName: String, order: Ordering.OperationOrder, filteredUids: Option[Seq[ERC20OperationUid]], offset: Int, limit: Int) =>
      "SELECT ercop.uid as erc_uid, ercop.ethereum_operation_uid as eth_uid, ercop.receiver, ercop.value, ercop.type, " +
        "ercop.date, ethop.transaction_hash, b.height as block_height, b.time as block_time, b.hash as block_hash, " +
        "o.amount, o.fees, o.senders, o.recipients, etx.gas_price, etx.gas_limit, etx.gas_used, etx.status, etx.confirmations " +
        "FROM accounts a, wallets w, operations o, ethereum_operations ethop, erc20_operations ercop, ethereum_transactions etx, blocks b " +
        s"WHERE w.name='$walletName' AND a.idx='$accountIndex' AND a.wallet_uid=w.uid " +
        "AND a.uid = o.account_uid " +
        "AND etx.block_uid=b.uid " +
        "AND ercop.ethereum_operation_uid = o.uid " +
        "AND ercop.ethereum_operation_uid = ethop.uid " +
        "AND ethop.transaction_uid = etx.transaction_uid " +
        filteredUids.fold("")(uids => s"AND ercop.uid IN ('${uids.mkString("','")}') ") +
        "ORDER BY o.date " + order.value +
        s" OFFSET $offset LIMIT $limit"

  /**
    * List operations from an account filtered by Uids
    */
  def listOperationsByUids(a: Account, w: Wallet, filteredUids: Option[Seq[OperationUid]], offset: Int, limit: Int): Future[Seq[OperationView]] = {
    logger.debug(s"Retrieving operations for account : ${accountPath(a, w)} - limit=$limit offset=$offset")
    val currency = w.getCurrency
    val currencyName = currency.getName
    val currencyFamily = currency.getWalletType
    var uids = Seq[OperationUid]()

    def retrievePartialOperations = {
      queryEthereumOperations[PartialEthOperation](a.getIndex, w.getName, filteredUids, offset, limit) {
        row => {
          val opUid = row.get[String]("uid")
          uids = uids :+ opUid
          PartialEthOperation(
            opUid, currencyName, currencyFamily.name(),
            row.get[Date]("date"),
            row.get[String]("transaction_hash"),
            row.getOption[Long]("block_height"),
            row.getOption[String]("block_hash"),
            row.getOption[Date]("block_time"),
            row.get[String]("type"),
            BigInt(row.get[String]("amount"), 16),
            BigInt(row.get[String]("fees"), 16),
            w.getName, a.getIndex,
            BigInt(row.get[String]("gas_price"), 16).toString(),
            BigInt(row.get[String]("gas_limit"), 16).toString(),
            BigInt(row.get[String]("gas_used"), 16).toString(),
            BigInt(row.get[String]("status")),
            BigInt(row.get[String]("confirmations")),
            row.get[String]("senders"),
            row.get[String]("recipients")
          )
        }
      }
    }

    for {
      operations <- retrievePartialOperations
      erc20 <- findErc20PartialView(a, w, Some(operations.map(_.uid)), 0, Int.MaxValue)
    } yield {
      operations.map(pop => {
        val blockView = (pop.blockHash, pop.blockHeight, pop.blockTime) match {
          case (Some(hash), Some(height), Some(time)) => Some(CommonBlockView(hash, height, time))
          case _ => None
        }
        val erc20Op = erc20.get(pop.uid) // TODO : get operationType
        val txView = EthereumTransactionView(blockView, pop.txHash, pop.recipient,
          pop.sender, pop.amount.toString, erc20Op, pop.gasPrice, pop.gasLimit, pop.date, pop.status.intValue())
        OperationView(pop.uid,
          currencyName, currencyFamily,
          None, pop.confirmations.longValue(),
          pop.date,
          pop.blockHeight,
          OperationType.valueOf(pop.opType),
          pop.amount.toString(),
          pop.fees.toString(),
          w.getName,
          a.getIndex,
          Seq(pop.sender),
          Seq(pop.recipient),
          Seq(pop.recipient).filter(a.getAccountKeychain.contains(_)), Some(txView))

      })
    }
  }

  /**
    * Find erc20 operations details from list of ETH operation uids
    * TODO : for a given eth op could we have several erc20 operations ?
    * If Yes Then we need to return Map[OperationUid, Seq[ERC20]]
    */
  private def findErc20PartialView(a: Account, w: Wallet, ethOperationUids: Option[Seq[OperationUid]],
                                  offset: Int, limit: Int): Future[Map[OperationUid, EthereumTransactionView.ERC20]] = {
    logger.info(s"Retrieving erc20 operations for account : ${accountPath(a, w)} - limit=$limit offset=$offset filtered by ${ethOperationUids.map(_.size)}")

    def retrieveERC20Operations = {
      queryERC20OperationsFromEthUids[(OperationUid, EthereumTransactionView.ERC20)](a.getIndex, w.getName, ethOperationUids, offset, limit) {
        row => {
          val ethOpUid = row.get[String]("eth_uid")
          val receiver = row.get[String]("receiver")
          val value = row.get[String]("value")
          (ethOpUid, EthereumTransactionView.ERC20(receiver, BigInt(value, 16)))
        }
      }
    }

    for {
      erc20 <- retrieveERC20Operations
    } yield erc20.toMap
  }

  private def findErc20FullOperationView(a: Account, w: Wallet, erc20OperationUids: Option[Seq[ERC20OperationUid]],
                                         offset: Int, limit: Int): Future[Seq[OperationView]] = {
    logger.info(s"Retrieving erc20 operations for account : ${accountPath(a, w)} - limit=$limit offset=$offset filtered by ${erc20OperationUids.map(_.size)}")

    queryERC20OperationsFullView(a, w, erc20OperationUids, offset, limit) {
      rowToFullOperationView(w, a.getIndex)
    }
  }

  private def findErc20FullOperationViewFromBlockHeight(a: Account, w: Wallet, blockHeight: Long,
                                                        offset: Int, limit: Int): Future[Seq[OperationView]] = {
    logger.info(s"Retrieving erc20 operations for account : ${accountPath(a, w)} - limit=$limit offset=$offset from height ${blockHeight}")

    queryERC20OperationsFromBlockHeightFullView(a, w, blockHeight, offset, limit) {
      rowToFullOperationView(w, a.getIndex)
    }
  }

  def toEIP55Address(address: String, currency: Currency): String = {
    val addr = Address.parse(address, currency)
    val addrStr = addr.toString
    addr.destroy()
    addrStr
  }

  /**
    * List operations from an account
    */
  def listAllOperations(a: Account, w: Wallet, offset: Int, limit: Int): Future[Seq[OperationView]] = {
    listOperationsByUids(a, w, None, offset, limit)
  }

  /**
    * Find Operation
    */
  def findOperationByUid(a: Account, w: Wallet, uid: OperationUid, offset: Int, limit: Int): Future[Option[OperationView]] = {
    listOperationsByUids(a, w, Some(Seq(uid)), offset, limit).map(_.headOption)
  }

  /**
    * List operations from an account filtered by Uids
    */
  def findOperationsByUids(a: Account, w: Wallet, filteredUids: Seq[OperationUid], offset: Int, limit: Int): Future[Seq[OperationView]] = {
    listOperationsByUids(a, w, Some(filteredUids), offset, limit)
  }

  /**
    * List operations erc20 from an erc20 account
    */
  override def listAllERC20Operations(a: Account, w: Wallet, erc20AccountUid: ERC20AccountUid, offset: Int, limit: Int): Future[Seq[OperationView]] = {
    findErc20FullOperationView(a, w, None, offset, limit)
  }

  /**
    * Find Erc20 Operation by Uid
    */
  override def findERC20OperationByUid(a: Account, w: Wallet, uid: ERC20OperationUid): Future[Option[OperationView]] = {
    findErc20FullOperationView(a, w, Some(Seq(uid)), 0, 1).map(_.headOption)
  }

  /**
    * List erc20 operations from an account filtered by Uids
    */
  override def findERC20OperationsByUids(a: Account, w: Wallet, ercOpUids: Seq[ERC20OperationUid], offset: Int, limit: Int): Future[Seq[OperationView]] = {
    findErc20FullOperationView(a, w, Some(ercOpUids), offset, limit)
  }

  /**
    * List erc20 operations from an account starting at specified block height
    */
  override def findERC20OperationsFromBlockHeight(a: Account, w: Wallet, blockHeight: Long, offset: Int, limit: Int): Future[Seq[OperationView]] = {
    findErc20FullOperationViewFromBlockHeight(a, w, blockHeight, offset, limit)
  }


  private def queryEthereumOperations[T](accountIndex: Int, walletName: String,
                                         filteredUids: Option[Seq[OperationUid]], offset: Int, limit: Int)(f: Row => T) = {
    db.executeQuery[T](ethOperationQuery(accountIndex, walletName, Ordering.Ascending, filteredUids, offset, limit))(f)
  }

  private def queryERC20OperationsFromEthUids[T](accountIndex: Int, walletName: String,
                                                 filteredUids: Option[Seq[OperationUid]], offset: Int, limit: Int)(f: Row => T) = {
    db.executeQuery[T](erc20OperationByEthUidsQuery(accountIndex, walletName, Ordering.Ascending, filteredUids, offset, limit))(f)
  }

  private def queryERC20OperationsFullView(a: Account, w: Wallet, filteredUids: Option[Seq[ERC20OperationUid]], offset: Int, limit: Int)(f: Row => OperationView): Future[Seq[OperationView]] = {
    db.executeQuery(ethOperationByErc20Uids(a.getIndex, w.getName, Ordering.Ascending, filteredUids, offset, limit))(f)
  }

  private def queryERC20OperationsFromBlockHeightFullView(a: Account, w: Wallet, blockHeight: Long, offset: Int, limit: Int)(f: Row => OperationView): Future[Seq[OperationView]] = {
    db.executeQuery(erc20OperationFromBlockHeightQuery(a.getIndex, w.getName, blockHeight, offset, limit))(f)
  }

  private def rowToFullOperationView(w: Wallet, accountIndex: Int)(row: Row) = {
    val ethOpUid = row.get[String]("eth_uid")
    // val ercOpUid = row.get[String]("erc_uid")
    val ercReceiver = row.get[String]("receiver")
    val ercValue = row.get[String]("value")
    val operationDate = row.get[Date]("date")
    val blockHash = row.getOption[String]("block_hash")
    val blockTime = row.getOption[Date]("block_time")
    val blockHeight = row.getOption[Long]("block_height")
    val blockView = (blockHash, blockHeight, blockTime) match {
      case (Some(hash), Some(height), Some(time)) => Some(CommonBlockView(hash, height, time))
      case _ => None
    }
    val ethSender = toEIP55Address(row.get[String]("senders"), w.getCurrency)
    val ethReceipient = toEIP55Address(row.get[String]("recipients"), w.getCurrency)
    val amount = BigInt(row.get[String]("amount"), 16)
    OperationView(ethOpUid, w.getCurrency.getName, currencyFamily, None,
      BigInt(row.get[String]("confirmations")).longValue(),
      operationDate,
      row.getOption[Long]("block_height"),
      OperationType.valueOf(row.get[String]("type")),
      amount.toString(),
      BigInt(row.get[String]("fees"), 16).toString(),
      w.getName, accountIndex,
      Seq(ethSender),
      Seq(ethReceipient),
      Seq.empty,
      Some(EthereumTransactionView(blockView, row.get[String]("transaction_hash"),
        ethReceipient, ethSender, amount.toString(),
        Some(ERC20(ercReceiver, BigInt(ercValue, 16))),
        BigInt(row.get[String]("gas_price"), 16).toString(),
        BigInt(row.get[String]("gas_limit"), 16).toString(),
        operationDate, BigInt(row.get[String]("status")).intValue()))
    )
  }

  private def accountPath(a: Account, w: Wallet) = s"${poolName}/${w.getName}/${a.getIndex}"

}
