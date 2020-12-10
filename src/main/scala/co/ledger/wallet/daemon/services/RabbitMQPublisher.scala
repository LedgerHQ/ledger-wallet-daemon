package co.ledger.wallet.daemon.services

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSelection
import co.ledger.core.{Account, ERC20LikeAccount, Wallet}
import co.ledger.wallet.daemon.context.ApplicationContext.IOPool
import co.ledger.wallet.daemon.models.Account._
import co.ledger.wallet.daemon.models.Currency._
import co.ledger.wallet.daemon.models.Operations.OperationView
import co.ledger.wallet.daemon.models.Pool
import com.newmotion.akka.rabbitmq.{Channel, ChannelMessage}
import com.twitter.finatra.json.FinatraObjectMapper
import com.twitter.inject.Logging

import scala.concurrent.Future


class RabbitMQPublisher(poolPublisher: ActorSelection) extends Logging with Publisher {

  private val totalOpPublished: AtomicInteger = new AtomicInteger(0)

  private val mapper = FinatraObjectMapper.create()

  private def publish(exchangeName: String, routingKeys: List[String], payload: Array[Byte]): Unit = {
    if (totalOpPublished.incrementAndGet() % 100 == 0) {
      logger.info(s"RabbitMQPublisher published ${totalOpPublished.get()} messages on exchange $exchangeName")
    }
    val publish: Channel => Unit = _.basicPublish(exchangeName, routingKeys.mkString("."), null, payload)
    poolPublisher ! ChannelMessage(publish, dropIfNoChannel = false)
  }

  def publishOperation(operationView: OperationView, account: Account, wallet: Wallet, poolName: String): Unit = {
    val payload = mapper.writeValueAsBytes(operationView)
    publish(poolName, getTransactionRoutingKeys(operationView, account, wallet.getCurrency.getName, poolName), payload)
  }

  def publishERC20Operation(operationView: OperationView, account: Account, wallet: Wallet, poolName: String): Unit = {
    val payload = mapper.writeValueAsBytes(operationView)
    publish(poolName, getERC20TransactionRoutingKeys(operationView, account, wallet.getCurrency.getName, poolName), payload)
  }

  def publishAccount(pool: Pool, account: Account, wallet: Wallet, syncStatus: SyncStatus): Future[Unit] = {
    accountPayload(pool, account, wallet, syncStatus).map { payload =>
      publish(pool.name, getAccountRoutingKeys(account, wallet, pool.name), payload)
    }
  }

  override def publishERC20Account(pool: Pool, erc20Account: ERC20LikeAccount, account: Account, wallet: Wallet, syncStatus: SyncStatus): Future[Unit] = {
    erc20AccountPayload(pool: Pool, erc20Account: ERC20LikeAccount, account: Account, wallet: Wallet, syncStatus: SyncStatus).map {
      payload => publish(pool.name, getAccountRoutingKeys(account, wallet, pool.name) ++ List("erc20"), payload)
    }
  }

  def publishDeletedOperation(uid: String, account: Account, wallet: Wallet, poolName: String): Future[Unit] = {
    Future {
      val routingKey = deleteOperationRoutingKeys(account, wallet, poolName)
      val payload = deleteOperationPayload(uid, account, wallet, poolName)
      publish(poolName, routingKey, payload)
    }
  }

  private def deleteOperationPayload(uid: String, account: Account, wallet: Wallet, poolName: String): Array[Byte] = {
    val map: Map[String, Any] = Map(
      "uid" -> uid,
      "account" -> account.getIndex(),
      "wallet" -> wallet.getName(),
      "poolName" -> poolName
    )
    mapper.writeValueAsBytes(map)
  }

  private def deleteOperationRoutingKeys(account: Account, wallet: Wallet, poolName: String): List[String] = {
    List(
      "transactions",
      poolName,
      wallet.getName(),
      account.getIndex().toString(),
      "delete"
    )
  }

  private def accountPayload(pool: Pool, account: Account, wallet: Wallet, syncStatus: SyncStatus): Future[Array[Byte]] = {
    account.accountView(pool, wallet, wallet.getCurrency.currencyView, syncStatus)
      .map(AccountRabbitMQView.fromAccountView)
      .map(mapper.writeValueAsBytes)
  }

  private def erc20AccountPayload(pool: Pool, erc20Account: ERC20LikeAccount, account: Account, wallet: Wallet, syncStatus: SyncStatus): Future[Array[Byte]] = {
    for {
      accountView <- account.accountView(pool, wallet, wallet.getCurrency.currencyView, syncStatus)
      erc20AccountView <- account.erc20AccountView(erc20Account, wallet, syncStatus)
    } yield mapper.writeValueAsBytes(ERC20AccountRabbitMQView.fromAccountViews(accountView, erc20AccountView))
  }

  private def getAccountRoutingKeys(account: Account, wallet: Wallet, poolName: String): List[String] = {
    List(
      "accounts",
      poolName,
      wallet.getCurrency.getName,
      account.getIndex.toString
    )
  }

  private def getERC20TransactionRoutingKeys(operationView: OperationView, account: Account, currencyName: String, poolName: String): List[String] = {
    List(
      "transactions",
      poolName,
      currencyName,
      account.getIndex.toString,
      "erc20",
      operationView.opType.toString.toLowerCase()
    )
  }

  private def getTransactionRoutingKeys(operationView: OperationView, account: Account, currencyName: String, poolName: String): List[String] = {
    List(
      "transactions",
      poolName,
      currencyName,
      account.getIndex.toString,
      operationView.opType.toString.toLowerCase
    )
  }
}
