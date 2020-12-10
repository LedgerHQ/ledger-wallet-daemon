package co.ledger.wallet.daemon.database

import co.ledger.wallet.daemon.async.MDCPropagatingExecutionContext.Implicits.global
import co.ledger.wallet.daemon.exceptions._
import co.ledger.wallet.daemon.models
import co.ledger.wallet.daemon.models.Wallet._
import co.ledger.wallet.daemon.models._
import co.ledger.wallet.daemon.utils.NativeLibLoader
import org.junit.Assert._
import org.junit.{BeforeClass, Test}
import org.scalatest.junit.AssertionsForJUnit

import scala.concurrent.Await
import scala.concurrent.duration.Duration

@Test
class DaemonCacheTest extends AssertionsForJUnit {

  import DaemonCacheTest._

  @Test def verifyGetPoolNotFound(): Unit = {
    val pool = Await.result(cache.getWalletPool(PoolInfo("pool_not_exist")), Duration.Inf)
    assert(pool.isEmpty)
  }

  @Test def verifyDeleteNotExistPool(): Unit = {
    Await.result(cache.deleteWalletPool(PoolInfo("pool_not_exist")), Duration.Inf)
  }


  @Test def verifyCreateAndGetPools(): Unit = {
    val pool11 = Await.result(cache.getWalletPool(PoolInfo("pool_a")), Duration.Inf)
    val pool12 = Await.result(cache.getWalletPool(PoolInfo("pool_b")), Duration.Inf)
    val pool13 = Await.result(cache.createWalletPool(PoolInfo("pool_c"), "config"), Duration.Inf)
    val pool1s = Await.result(cache.getAllPools, Duration.Inf)
    assertTrue(pool1s.contains(pool11.get))
    assertTrue(pool1s.contains(pool12.get))
    assertTrue(pool1s.contains(pool13))
  }

  @Test def verifyCreateAndDeletePool(): Unit = {
    val poolRandom = Await.result(cache.createWalletPool(PoolInfo(createDeletePoolName), "config"), Duration.Inf)
    val beforeDeletion = Await.result(cache.getAllPools, Duration.Inf)
    assertTrue(beforeDeletion.contains(poolRandom))

    val afterDeletion = Await.result(cache.deleteWalletPool(PoolInfo(poolRandom.name)).flatMap(_ => cache.getAllPools), Duration.Inf)
    assertFalse(afterDeletion.contains(poolRandom))
  }

  @Test def verifyGetCurrencies(): Unit = {
    val currencies = Await.result(cache.getCurrencies(PoolInfo("pool_a")), Duration.Inf)
    assert(currencies.nonEmpty)
    val currency = Await.result(cache.getCurrency("bitcoin", PoolInfo("pool_b")), Duration.Inf)
    assert(currency.isDefined)
  }

  @Test def verifyGetFreshAddressesFromNonExistingAccount(): Unit = {
    val addresses: Seq[FreshAddressView] = Await.result(cache.getFreshAddresses(models.AccountInfo(0, walletName, poolName)), Duration.Inf)
    assert(addresses.nonEmpty)
    try {
      Await.result(cache.getFreshAddresses(models.AccountInfo(1122, walletName, poolName)), Duration.Inf)
      fail()
    } catch {
      case _: AccountNotFoundException => // expected
    }
  }

  // FIXME: Broken test
  // @Test
  def verifyGetAccountOperations(): Unit = {
    val pool1 = Await.result(DefaultDaemonCache.dbDao.getPoolByName(poolName), Duration.Inf)
    val withTxs = Await.result(cache.getAccountOperations(1, 1, AccountInfo(0, walletName, pool1.get.name)), Duration.Inf)
    withTxs.operations.foreach(op => assertNotNull(op.transaction))
    val ops = Await.result(cache.getAccountOperations(2, 0, AccountInfo(0, walletName, pool1.get.name)), Duration.Inf)
    assert(ops.previous.isEmpty)
    assert(2 === ops.operations.size)
    assert(ops.previous.isEmpty)
    ops.operations.foreach(op => assert(op.transaction.isEmpty))
    val nextOps = Await.result(cache.getNextBatchAccountOperations(ops.next.get, 0, AccountInfo(0, walletName, pool1.get.name)), Duration.Inf)

    val previousOfNextOps = Await.result(cache.getPreviousBatchAccountOperations(nextOps.previous.get, 0, AccountInfo(0, walletName, pool1.get.name)), Duration.Inf)
    assert(ops === previousOfNextOps)

    val nextnextOps = Await.result(cache.getNextBatchAccountOperations(nextOps.next.get, 0, AccountInfo(0, walletName, pool1.get.name)), Duration.Inf)

    val previousOfNextNextOps = Await.result(cache.getPreviousBatchAccountOperations(nextnextOps.previous.get, 0, AccountInfo(0, walletName, pool1.get.name)), Duration.Inf)
    assert(nextOps === previousOfNextNextOps)

    val theVeryFirstOps = Await.result(cache.getPreviousBatchAccountOperations(previousOfNextNextOps.previous.get, 0, AccountInfo(0, walletName, pool1.get.name)), Duration.Inf)
    assert(ops === theVeryFirstOps)

    val maxi = Await.result(cache.getAccountOperations(Int.MaxValue, 0, AccountInfo(0, walletName, pool1.get.name)), Duration.Inf)
    assert(maxi.operations.size < Int.MaxValue)
    assert(maxi.previous.isEmpty)
    assert(maxi.next.isEmpty)
  }
}

object DaemonCacheTest {

  private val cache: DefaultDaemonCache = new DefaultDaemonCache()
  private val walletName = "DaemonCacheTestWallet"
  private val poolName = "default_test_pool"
  private val createDeletePoolName = "create_delete_pool"

  @BeforeClass def initialization(): Unit = {
    NativeLibLoader.loadLibs()
    Await.result(cache.dbMigration, Duration.Inf)
    Await.result(cache.createWalletPool(PoolInfo("pool_a"), ""), Duration.Inf)
    Await.result(cache.createWalletPool(PoolInfo("pool_b"), ""), Duration.Inf)
    Await.result(cache.createWalletPool(PoolInfo(createDeletePoolName), ""), Duration.Inf)
    Await.result(cache.createWalletPool(PoolInfo(poolName), ""), Duration.Inf)
    Await.result(cache.createWallet("bitcoin", WalletInfo(walletName, poolName), isNativeSegwit = false), Duration.Inf)
    val walletInfo = WalletInfo(walletName, poolName)
    Await.result(cache.withWallet(walletInfo) {w =>
      w.addAccountIfNotExist(
      AccountDerivationView(0, List(
        DerivationView("44'/0'/0'", "main", Option("0437bc83a377ea025e53eafcd18f299268d1cecae89b4f15401926a0f8b006c0f7ee1b995047b3e15959c5d10dd1563e22a2e6e4be9572aa7078e32f317677a901"), Option("d1bb833ecd3beed6ec5f6aa79d3a424d53f5b99147b21dbc00456b05bc978a71")),
        DerivationView("44'/0'", "main", Option("0437bc83a377ea025e53eafcd18f299268d1cecae89b4f15401926a0f8b006c0f7ee1b995047b3e15959c5d10dd1563e22a2e6e4be9572aa7078e32f317677a901"), Option("d1bb833ecd3beed6ec5f6aa79d3a424d53f5b99147b21dbc00456b05bc978a71")))))}
      , Duration.Inf)
    Await.result(cache.getAccountOperations(1, 1, AccountInfo(0, walletName, poolName)), Duration.Inf)
  }
}
