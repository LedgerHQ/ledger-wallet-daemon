package co.ledger.wallet.daemon.models

import co.ledger.wallet.daemon.async.MDCPropagatingExecutionContext.Implicits.global
import co.ledger.wallet.daemon.database.PoolDto
import co.ledger.wallet.daemon.utils.NativeLibLoader
import org.junit.Test
import org.scalatest.junit.AssertionsForJUnit

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class PoolTest extends AssertionsForJUnit {

  NativeLibLoader.loadLibs()

  val poolName = "walletpool_test"
  private val testPool = Pool.newPoolInstance(PoolDto(poolName, "", Option(0L))).get
  private val notExistingWallet = Await.result(testPool.wallet("pool_not_exist"), Duration.Inf)
  private val samePool = Pool.newPoolInstance(PoolDto(poolName, "", Option(0L))).get

  private val wallet = Await.result(testPool.addWalletIfNotExist("test_wallet", "bitcoin", isNativeSegwit = false).flatMap { testWallet =>
    testPool.wallet("test_wallet").flatMap { sameWallet =>
      assert(Option((testWallet.getName, testWallet.getCurrency.getName)) === sameWallet.map(wallet => (wallet.getName, wallet.getCurrency.getName)))
      assert(WalletPoolView(poolName, 1) === Await.result(testPool.view, Duration.Inf))
      testPool.wallets(0, Int.MaxValue).map { wallets =>
        assert(wallets._1 === 1)
        assert((testWallet.getName, testWallet.getCurrency.getName) === wallets._2.map(w => (w.getName, w.getCurrency.getName)).head)
        testWallet
      }
    }
  }, Duration.Inf)

  @Test def verifyWalletInPool(): Unit = {
    assert(Option((wallet.getName, wallet.getCurrency.getName)) === Await.result(samePool.wallet("test_wallet"), Duration.Inf).map(w => (w.getName, w.getCurrency.getName)))
    assert(testPool.name === poolName)
    assert(WalletPoolView(poolName, 1) === Await.result(samePool.view, Duration.Inf))
    assert(notExistingWallet.isEmpty)
    assert(wallet !== "wallet")
    assert(testPool !== wallet)
  }

  @Test def verifyWalletsInPool(): Unit = {
    val (count, wallets) = Await.result(testPool.wallets(0, 100), Duration.Inf)
    assert(count == 1)
    assert(wallets.size == 1)
    assert(List((wallet.getName, wallet.getCurrency.getName)) == wallets.map(w => (w.getName, w.getCurrency.getName)))
  }

}
