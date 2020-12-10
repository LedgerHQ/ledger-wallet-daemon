package co.ledger.wallet.daemon.models


import co.ledger.core
import co.ledger.wallet.daemon.async.MDCPropagatingExecutionContext.Implicits.global
import co.ledger.wallet.daemon.database.PoolDto
import co.ledger.wallet.daemon.exceptions.UnsupportedNativeSegwitException
import co.ledger.wallet.daemon.models.Account._
import co.ledger.wallet.daemon.models.Currency.RichCoreCurrency
import co.ledger.wallet.daemon.models.Wallet.RichCoreWallet
import co.ledger.wallet.daemon.services.Synced
import co.ledger.wallet.daemon.utils.NativeLibLoader
import org.junit.Test
import org.scalatest.junit.AssertionsForJUnit

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

class WalletTest extends AssertionsForJUnit {
  NativeLibLoader.loadLibs()

  val poolName = "default_test_pool"
  private val PUBKEYS = List[String](
    "0437bc83a377ea025e53eafcd18f299268d1cecae89b4f15401926a0f8b006c0f7ee1b995047b3e15959c5d10dd1563e22a2e6e4be9572aa7078e32f317677a901",
    "04fb60043afe80ee1aeb0160e2aafc94690fb4427343e8d4bf410105b1121f7a44a311668fa80a7a341554a4ef5262bc6ebd8cc981b8b600dafd40f7682edb5b3b")
  private val CHAINCODES = List[String](
    "d1bb833ecd3beed6ec5f6aa79d3a424d53f5b99147b21dbc00456b05bc978a71",
    "88c2281acd51737c912af74cc1d1a8ba564eb7925e0d58a5500b004ba76099cb")

  @Test def verifyWalletActivities(): Unit = {
    val testPool = Pool.newPoolInstance(PoolDto(poolName, "", Option(0L))).get

    val testWallet = Await.result(testPool.addWalletIfNotExist("test_walletbtc", "bitcoin", isNativeSegwit = false), Duration.Inf)

    val testAccount: core.Account = Await.result(testWallet.accounts.flatMap { as =>
      assert(as.isEmpty)
      testWallet.accountCreationInfo(None).map { derivation =>
        AccountDerivationView(
          derivation.index,
          derivation.view.derivations.zipWithIndex.map { d =>
            DerivationView(d._1.path, d._1.owner, Option(PUBKEYS(d._2)), Option(CHAINCODES(d._2)))
          }
        )
      }.flatMap { info =>
        testWallet.addAccountIfNotExist(info)
      }
    }, Duration.Inf)

    val account6: core.Account = Await.result(
      testWallet.accountCreationInfo(Option(6)).map { derivation =>
        AccountDerivationView(
          derivation.index,
          derivation.view.derivations.zipWithIndex.map { d =>
            DerivationView(d._1.path, d._1.owner, Option(PUBKEYS(d._2)), Option(CHAINCODES(d._2)))
          }
        )
      }.flatMap { info => testWallet.addAccountIfNotExist(info) }, Duration.Inf)

    val account4: core.Account = Await.result(
      testWallet.accountCreationInfo(Option(4)).map { derivation =>
        AccountDerivationView(
          derivation.index,
          derivation.view.derivations.zipWithIndex.map { d =>
            DerivationView(d._1.path, d._1.owner, Option(PUBKEYS(d._2)), Option(CHAINCODES(d._2)))
          }
        )
      }.flatMap { info => testWallet.addAccountIfNotExist(info) }, Duration.Inf)

    val accounts = Await.result(testWallet.accounts, Duration.Inf)
    assert(3 === accounts.size)
    assert(testAccount.getIndex === accounts.head.getIndex)
    assert(account6.getIndex === accounts.tail.head.getIndex)
    assert(account4.getIndex === accounts.tail.tail.head.getIndex)
    val account = Await.result(testWallet.account(testAccount.getIndex), Duration.Inf)
    assert(account.map(_.getIndex) === accounts.headOption.map(_.getIndex))
    val walletView = Await.result(testWallet.walletView, Duration.Inf)
    val accountView = Await.result(testAccount.accountView(testPool, testWallet, testWallet.getCurrency.currencyView, Synced(0)), Duration.Inf)
    assert(walletView.balance === accountView.index)
    assert(0 === testAccount.getIndex)
    assert(6 === account6.getIndex)
    assert(4 === account4.getIndex)
  }

  @Test def verifyWalletSupportNativeSegwit(): Unit = {
    val testPool = Pool.newPoolInstance(PoolDto(poolName, "", Option(0L))).get

    val testWalletBitcoinNativeSegwit = Await.result(testPool.addWalletIfNotExist("test_wallet_bitcoin_native_segwit", "bitcoin", isNativeSegwit = true), Duration.Inf)

    val testWalletInvalidCurrencyForNativeSegwit = Await.result(
      testPool.addWalletIfNotExist("test_wallet_ethereum_native_segwit", "ethereum", isNativeSegwit = true)
        .map(Success(_))
        .recover {
          case e: UnsupportedNativeSegwitException => Failure(e)
        },
      Duration.Inf
    )

    // Bitcoin native segwit should use keychain engine 'BIP173_P2WPKH'
    assert(testWalletBitcoinNativeSegwit.getConfiguration.getString("KEYCHAIN_ENGINE") === "BIP173_P2WPKH")
    // Should failed for ethereum
    assert(testWalletInvalidCurrencyForNativeSegwit === Failure(UnsupportedNativeSegwitException("ethereum")))
  }

}
