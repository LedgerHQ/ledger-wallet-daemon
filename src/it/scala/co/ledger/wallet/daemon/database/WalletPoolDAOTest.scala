package co.ledger.wallet.daemon.database

import co.ledger.core.{Account, Wallet}
import co.ledger.wallet.daemon.async.MDCPropagatingExecutionContext.Implicits.global
import co.ledger.wallet.daemon.database.core.WalletPoolDao
import co.ledger.wallet.daemon.models.Account._
import co.ledger.wallet.daemon.models.Currency._
import co.ledger.wallet.daemon.models.Wallet._
import co.ledger.wallet.daemon.models.coins.EthereumTransactionView
import co.ledger.wallet.daemon.services.Synced
import co.ledger.wallet.daemon.models.{AccountDerivationView, DerivationView, AccountExtendedDerivationView, ExtendedDerivationView, Pool, PoolInfo}
import co.ledger.wallet.daemon.utils.NativeLibLoader
import com.twitter.inject.Logging
import com.twitter.util.{Await => TwitterAwait}
import org.junit.Test
import org.scalatest.junit.AssertionsForJUnit

import scala.concurrent.Await
import scala.concurrent.duration.Duration

@Test
class WalletPoolDAOTest extends AssertionsForJUnit with Logging {
  NativeLibLoader.loadLibs()

  val xpubBtc1: String = "xpub6D4waFVPfPCpefXd5Rwb9TRuhoW7WZfYTS4cjv6Cw7ZBvFURHFFVYV2GF8hD36r31iDwBuP71TAEmy9596SnA7Hi6bgCLo6DYb7UUVqWWPA"
  val xpubEth1: String = "xpub6BemYiVNp19a2agTdnZuYi28C7ZwYx7nExX3o6owBp9Acsg2v3QnyioYXDeNV7CZa53SFhoLpGmNkE3wLrdTQ53haoedVhwGXP2MLkNTyoq"
  val stellarPubKey1: String = "a1083d11720853a2c476a07e29b64e0f9eb2ff894f1e485628faa7b63de77a4f"
  val daemonCache: DaemonCache = new DefaultDaemonCache()
  val poolName = "walletpooldao"
  val pool: Pool = Await.result(daemonCache.createWalletPool(PoolInfo(poolName), ""), Duration.Inf) // Pool.newPoolInstance(PoolDto(poolName, "", Some(1))).get

  def createWallet(walletName: String): Wallet = {
    Await.result(pool.addWalletIfNotExist(walletName, walletName, isNativeSegwit = false), Duration.Inf)
  }

  def createAccountAndSync(wallet: Wallet, derivations: AccountExtendedDerivationView): Account = {
    val account = Await.result(wallet.addAccountIfNotExist(derivations), Duration.Inf)
    Await.result(account.sync(poolName, wallet.getName), Duration.Inf)
    account
  }

  def createAccountAndSync(wallet: Wallet, derivations: AccountDerivationView): Account = {
    val account = Await.result(wallet.addAccountIfNotExist(derivations), Duration.Inf)
    Await.result(account.sync(poolName, wallet.getName), Duration.Inf)
    account
  }

  @Test def testBitcoinWallet(): Unit = {
    val poolDao = new WalletPoolDao(poolName)
    val walletName = "bitcoin"

    val wallet = createWallet(walletName)
    val derivations = AccountExtendedDerivationView(0,
      Seq[ExtendedDerivationView](ExtendedDerivationView("44'/0'/0'", "main", Some(xpubBtc1))))

    val account = createAccountAndSync(wallet, derivations)

    val allOperations = TwitterAwait.result(poolDao.listAllOperations(account, wallet, 0, 1000))
    assert(allOperations.size === 596)

    val filteredOperations = TwitterAwait.result(
      poolDao.findOperationsByUids(account, wallet,
        Seq(
          "313b7bd7ae0eb9b2b45b1be568de5e4d6f045f9bf049263c90d9f97814c647fb",
          "9c475196a18a6e0b8ccd1e472a218e4234ba5af2b6bfc0b8611beff6eac045ab",
          "37ffcc3a2ad0051111c533568b7a4dc4b87376ee8e6f5c7bf393bd456040c5a0"
        ), 0, 100))
    assert(filteredOperations.size == 3)
    val filteredOperationsWithLimit = TwitterAwait.result(
      poolDao.findOperationsByUids(account, wallet,
        Seq(
          "313b7bd7ae0eb9b2b45b1be568de5e4d6f045f9bf049263c90d9f97814c647fb",
          "9c475196a18a6e0b8ccd1e472a218e4234ba5af2b6bfc0b8611beff6eac045ab",
          "37ffcc3a2ad0051111c533568b7a4dc4b87376ee8e6f5c7bf393bd456040c5a0"
        ), 0, 2))
    assert(filteredOperationsWithLimit.size == 2)
    val filteredOperationsWithOffset = TwitterAwait.result(
      poolDao.findOperationsByUids(account, wallet,
        Seq(
          "313b7bd7ae0eb9b2b45b1be568de5e4d6f045f9bf049263c90d9f97814c647fb",
          "9c475196a18a6e0b8ccd1e472a218e4234ba5af2b6bfc0b8611beff6eac045ab",
          "37ffcc3a2ad0051111c533568b7a4dc4b87376ee8e6f5c7bf393bd456040c5a0"
        ), 1, 10))
    assert(filteredOperationsWithOffset.size == 2)
    val opCounts = TwitterAwait.result(poolDao.countOperations(account, wallet))
    val countByType = allOperations.groupBy(_.opType).mapValues(_.size)
    assert(opCounts == countByType)
    assert(opCounts.values.sum == allOperations.size)

    logger.info("Account view : " +
      s"${Await.result(account.accountView(pool, wallet, wallet.getCurrency.currencyView, Synced(0L)), Duration.Inf)}")
  }

  @Test def testEthereumWallet(): Unit = {
    val poolDao = new WalletPoolDao(poolName)
    val walletName = "ethereum"

    val wallet = createWallet(walletName)
    val derivations = AccountExtendedDerivationView(0,
      Seq[ExtendedDerivationView](ExtendedDerivationView("44'/60'/0'", "main", Some(xpubEth1))))

    val account = createAccountAndSync(wallet, derivations)

    val allOperations = TwitterAwait.result(poolDao.listAllOperations(account, wallet, 0, 100))
    logger.info(s" All operations : ${allOperations.size}")

    val filteredOperations = TwitterAwait.result(
      poolDao.findOperationsByUids(account, wallet,
        Seq(
          "ab641a57457e5f101fac21b5b1c400ae6e5b8c4c719f3c76f216a15f37436cc3",
          "1d62e8909bdb2051efd824f776e658afbf194de7b3566c6716226227f48102f4",
          "76d99345d2e2cdc7a1dc8ed63ff98aba1ccd4ffe8746cad2e66d2b5382e446cf"
        ), 0, 100))
    assert(filteredOperations.size == 3)

    // Expect 2 ERC20 operations
    val erc20OperationViews = allOperations.filter(_.transaction.exists(_.asInstanceOf[EthereumTransactionView].erc20.isDefined))
    // https://etherscan.io/address/0xD7838451bc7258Da7a9213e2bCcCc02079463A42#tokentxns
    assert(erc20OperationViews.size == 21)

    val erc20OpUids = Seq("d5ae76fa7d4d7e42f935e5bbbf3e4bb2b0940e9af14476d6a504509180c23642", "b04b9f3e7837dd33c04f7518276248d7a64ba0c3e3959ad990f0b26401d0761c")
    val erc20ByUids = TwitterAwait.result(poolDao.findERC20OperationsByUids(account, wallet, erc20OpUids, 0, Int.MaxValue))

    assert(erc20ByUids.size == 2)
    assert(erc20ByUids.map(_.uid).toSet.subsetOf(erc20OperationViews.map(_.uid).toSet))
  }

  @Test def testStellarWallet(): Unit = {
    val poolDao = new WalletPoolDao(poolName)
    val walletName = "stellar"

    val wallet = createWallet(walletName)
    val derivations = AccountDerivationView(0, Seq(DerivationView("44'/148'/0'", "main", Some(stellarPubKey1), Some("00"))))

    val account = createAccountAndSync(wallet, derivations)

    val allOperations = TwitterAwait.result(poolDao.listAllOperations(account, wallet, 0, 100))
    logger.info(s" All operations : ${allOperations.size}")

    val filteredOperations = TwitterAwait.result(
      poolDao.findOperationsByUids(account, wallet,
        Seq(
          "b990bd8b05cdbfb9b4b04bf1708db4e25116751f6ca1207d43ff970752d19dcf",
          "e484cc391a75f5b6931dc9534276cda0c6d70438f6db9ff193c6e9c28bac893e",
          "42c6418c9e51a30cceb6ed4456089b3ca6f451e5c42fa5b2586fb356b8cb6a76"
        ), 0, 100))
    assert(filteredOperations.size == 3)
  }

}
