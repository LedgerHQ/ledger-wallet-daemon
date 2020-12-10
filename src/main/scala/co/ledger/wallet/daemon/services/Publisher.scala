package co.ledger.wallet.daemon.services

import co.ledger.core
import co.ledger.core._
import co.ledger.wallet.daemon.context.ApplicationContext.IOPool
import co.ledger.wallet.daemon.models.Operations.OperationView
import co.ledger.wallet.daemon.models.{AccountView, CurrencyView, ERC20FullAccountView, Pool}
import com.fasterxml.jackson.annotation.JsonProperty
import com.twitter.inject.Logging

import scala.collection.JavaConverters._
import scala.concurrent.Future


trait Publisher {
  def publishOperation(op: OperationView, account: Account, wallet: Wallet, poolName: String): Unit

  def publishERC20Operation(op: OperationView, account: Account, wallet: Wallet, poolName: String): Unit

  def publishAccount(pool: Pool, account: Account, wallet: Wallet, syncStatus: SyncStatus): Future[Unit]

  def publishERC20Account(pool: Pool, erc20Account: ERC20LikeAccount, account: Account, wallet: Wallet, syncStatus: SyncStatus): Future[Unit]

  def publishERC20Accounts(pool: Pool, account: Account, wallet: Wallet, syncStatus: SyncStatus): Future[Unit] = {
    val ethAccount = account.asEthereumLikeAccount()
    Future.sequence {
      ethAccount.getERC20Accounts.asScala.map {
        erc20Account => publishERC20Account(pool, erc20Account, account, wallet, syncStatus)
      }
    }.map(_ => Unit)
  }

  def publishDeletedOperation(uid: String, account: Account, wallet: Wallet, poolName: String): Future[Unit]

}


sealed trait SyncStatus {
  def value: String
}

case class Synced(atHeight: Long) extends SyncStatus {
  @JsonProperty("value")
  def value: String = "synced"
}

case class Syncing(fromHeight: Long, currentHeight: Long) extends SyncStatus {
  @JsonProperty("value")
  def value: String = "syncing"
}

case class FailedToSync(reason: String) extends SyncStatus {
  @JsonProperty("value")
  def value: String = "failed"
}

/*
 * targetHeight is the height of the most recent operation of the account before the resync.
 * currentHeight is the height of the most recent operation of the account during resyncing.
 * they serve as a progress indicator
 */
case class Resyncing(@JsonProperty("sync_status_target") targetOpCount: Long,
                     @JsonProperty("synOperationCounterc_status_current") currentOpCount: Long)
  extends SyncStatus {
  @JsonProperty("value")
  def value: String = "resyncing"
}

sealed trait AccountRabbitMQ {
  def currency: CurrencyRabbitMQ
}

final case class AccountRabbitMQView(
                                      @JsonProperty("wallet_name") walletName: String,
                                      @JsonProperty("index") index: Int,
                                      @JsonProperty("balance") balance: scala.BigInt,
                                      @JsonProperty("currency") currency: CurrencyRabbitMQView,
                                      @JsonProperty("status") status: SyncStatus
                                    ) extends AccountRabbitMQ

case object AccountRabbitMQView {
  def fromAccountView(accountView: AccountView): AccountRabbitMQView = {
    AccountRabbitMQView(
      walletName = accountView.walletName,
      index = accountView.index,
      balance = accountView.balance,
      currency = CurrencyRabbitMQView.fromCurrencyView(accountView.currency),
      status = accountView.status
    )
  }
}

case class ERC20AccountRabbitMQView(
                                     @JsonProperty("wallet_name") walletName: String,
                                     @JsonProperty("index") index: Int,
                                     @JsonProperty("balance") balance: scala.BigInt,
                                     @JsonProperty("status") status: SyncStatus,
                                     @JsonProperty("currency") currency: ERC20CurrencyRabbitMQView
                                   ) extends AccountRabbitMQ

case object ERC20AccountRabbitMQView {
  def fromAccountViews(accountView: AccountView, erc20AccountView: ERC20FullAccountView): ERC20AccountRabbitMQView = {
    ERC20AccountRabbitMQView(
      balance = accountView.balance,
      currency = ERC20CurrencyRabbitMQView.fromERC20FullAccountView(accountView, erc20AccountView),
      status = accountView.status,
      walletName = accountView.walletName,
      index = accountView.index
    )
  }
}

sealed trait CurrencyRabbitMQ

final case class CurrencyRabbitMQView(
                                       @JsonProperty("name") name: String,
                                       @JsonProperty("family") family: core.WalletType
                                     ) extends CurrencyRabbitMQ

case object CurrencyRabbitMQView {
  def fromCurrencyView(currencyView: CurrencyView): CurrencyRabbitMQView = {
    CurrencyRabbitMQView(name = currencyView.name, family = currencyView.family)
  }
}

final case class ERC20CurrencyRabbitMQView(
                                            @JsonProperty("name") name: String,
                                            @JsonProperty("family") family: core.WalletType,
                                            @JsonProperty("contract_address") contractAddress: String
                                          ) extends CurrencyRabbitMQ

case object ERC20CurrencyRabbitMQView {
  def fromERC20FullAccountView(accountView: AccountView, ECR20AccountView: ERC20FullAccountView): ERC20CurrencyRabbitMQView = {
    ERC20CurrencyRabbitMQView(
      name = accountView.currency.name,
      family = accountView.currency.family,
      contractAddress = ECR20AccountView.contractAddress
    )
  }
}

// Dummy publisher that do nothing but log
class DummyPublisher extends Publisher with Logging {
  override def publishOperation(op: OperationView, account: Account, wallet: Wallet, poolName: String): Unit = {
    info(s"publish operation ${op.uid} of account:${account.getIndex}, wallet:${wallet.getName}, pool:$poolName")
  }

  override def publishERC20Operation(op: OperationView, account: Account, wallet: Wallet, poolName: String): Unit = {
    info(s"publish erc20 operation ${op.uid} of account:${account.getIndex}, wallet:${wallet.getName}, pool:$poolName")
  }

  override def publishAccount(pool: Pool, account: Account, wallet: Wallet, syncStatus: SyncStatus): Future[Unit] = {
    Future.successful(
      info(s"publish pool:${pool.name} ,account:${account.getIndex}, wallet:${wallet.getName}, syncStatus: $syncStatus")
    )
  }

  override def publishERC20Account(pool: Pool, erc20Account: ERC20LikeAccount, account: Account, wallet: Wallet, syncStatus: SyncStatus): Future[Unit] = {
    Future.successful(
      info(s"publish erc20 balance token=${erc20Account.getToken} index=${account.getIndex} wallet=${wallet.getName} pool=${pool.name}")
    )
  }

  override def publishDeletedOperation(uid: String, account: Account, wallet: Wallet, poolName: String): Future[Unit] = {
    Future.successful {
      info(s"delete operation $uid for account:${account.getIndex}, wallet:${wallet.getName}, pool:$poolName")
    }
  }
}
