package co.ledger.wallet.daemon.api

import co.ledger.wallet.daemon.controllers.responses.{ErrorCode, ErrorResponseBody}
import co.ledger.wallet.daemon.models
import co.ledger.wallet.daemon.models.WalletPoolView
import co.ledger.wallet.daemon.utils.APIFeatureTest
import com.twitter.finagle.http.Status

class WalletPoolsApiTest extends APIFeatureTest {
  val poolNameA = "pool_a"
  val poolNameB = "pool_b"
  val poolNameC = "pool_c"

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }

  test("WalletPoolsApi#Create and list single pool") {
    createPool(poolNameA)
    try {
      val pools = parse[List[models.WalletPoolView]](getPools())
      val pool = parse[models.WalletPoolView](getPool(poolNameA))
      assert(pools.contains(pool))
    } finally {
      deletePool(poolNameA)
    }
  }

  test("WalletPoolsApi#Create pool with invalid name") {
    val wrongName = "my_pool; drop table my_pool,"
    try {
      createPool(wrongName, Status.BadRequest)
    } finally {
      // If status is Status.BadRequest, this do nothing
      deletePoolIfExists(wrongName)
    }
  }

  test("WalletPoolsApi#Create and list multiple pool") {
    createPool(poolNameB)
    createPool(poolNameC)
    val pools = parse[List[models.WalletPoolView]](getPools())
    List(WalletPoolView(poolNameC, 0), WalletPoolView(poolNameB, 0)).map { pool => assert(pools.contains(pool)) }
    deletePool(poolNameC)
    deletePool(poolNameB)
    val pools2 = parse[List[models.WalletPoolView]](getPools())
    List(WalletPoolView(poolNameB, 0), WalletPoolView(poolNameB, 0))
      .foreach { pool => assert(!pools2.contains(pool)) }
  }

  test("WalletPoolsApi#Get single pool") {
    val response = createPool(poolNameA)
    assert(server.mapper.objectMapper.readValue[models.WalletPoolView](response.contentString) == WalletPoolView(poolNameA, 0))
    val pool = parse[models.WalletPoolView](getPool(poolNameA))
    assert(pool == WalletPoolView(poolNameA, 0))
    deletePool(poolNameA)
  }

  test("WalletPoolsApi#Get and delete non-exist pool return not found") {
    assert(
      server.mapper.objectMapper.readValue[ErrorResponseBody](getPool("not_exist_pool", Status.NotFound).contentString).rc
        == ErrorCode.Not_Found)
    deletePool("another_not_exist_pool", Status.BadRequest)
  }

  test("WalletPoolsApi#Create same pool twice return ok") {
    assert(
      server.mapper.objectMapper.readValue[models.WalletPoolView](createPool(poolNameB).contentString)
        == WalletPoolView(poolNameB, 0))
    assert(
      server.mapper.objectMapper.readValue[models.WalletPoolView](createPool(poolNameB).contentString)
        == WalletPoolView(poolNameB, 0))
    deletePool(poolNameB)
  }

}
