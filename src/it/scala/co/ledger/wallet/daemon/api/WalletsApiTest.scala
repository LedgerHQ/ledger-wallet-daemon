package co.ledger.wallet.daemon.api

import co.ledger.wallet.daemon.controllers.responses.{ErrorCode, ErrorResponseBody}
import co.ledger.wallet.daemon.exceptions.ErrorCodes
import co.ledger.wallet.daemon.models.{WalletView, WalletsViewWithCount}
import co.ledger.wallet.daemon.utils.APIFeatureTest
import com.twitter.finagle.http.{Response, Status}

class WalletsApiTest extends APIFeatureTest {
  private val poolName = "default_test_pool"

  override def beforeAll(): Unit = {
    super.beforeAll()
    createPool(poolName)
  }

  override def afterAll(): Unit = {
    deletePool(poolName)
    super.afterAll()
  }

  test("WalletsApi#Create then Get same wallet from pool Return OK") {
    val createdW = walletFromResponse(assertWalletCreation(poolName, "my_wallet", "bitcoin", Status.Ok))
    assert("my_wallet" === createdW.name)
    assert("bitcoin" === createdW.currency.name)
    val getW = walletFromResponse(assertGetWallet(poolName, "my_wallet", Status.Ok))
    assert(createdW === getW)
  }

  test("WalletsApi#Create bitcoin native segwit wallet then Get same wallet from pool Return OK") {
    val createdW = walletFromResponse(assertWalletNativeSegwitCreation(poolName, "my_wallet_bitcoin_native_segwit", "bitcoin", Status.Ok))
    assert("my_wallet_bitcoin_native_segwit" === createdW.name)
    assert("bitcoin" === createdW.currency.name)
    val getW = walletFromResponse(assertGetWallet(poolName, "my_wallet_bitcoin_native_segwit", Status.Ok))
    assert(createdW === getW)
  }

  test("WalletsApi#Create native segwit wallet for unsupported currency Return Bad Request") {
    val expectedErr = ErrorResponseBody(ErrorCode.Bad_Request, Map("response"->"Native segwit not supported for currency ethereum"))
    val postWalletErr = server.mapper.objectMapper.readValue[ErrorResponseBody](
      assertWalletNativeSegwitCreation(poolName, "my_wallet_ethereum_native_segwit", "ethereum", Status.BadRequest).contentString
    )
    assert(postWalletErr.rc === expectedErr.rc)
    assert(postWalletErr.msg.getOrElse("error_code", 0) === ErrorCodes.INVALID_CURRENCY_FOR_NATIVE_SEGWIT)
  }

  test("WalletsApi#Create then Get same wallet from pool Return OK (for bitcoin testnet)") {
    val createdW = walletFromResponse(assertWalletCreation(poolName, "my_testnet_wallet", "bitcoin_testnet", Status.Ok))
    assert("my_testnet_wallet" === createdW.name)
    assert("bitcoin_testnet" === createdW.currency.name)
    val getW = walletFromResponse(assertGetWallet(poolName, "my_testnet_wallet", Status.Ok))
    assert(createdW === getW)
  }

  test("WalletsApi#Get non exist wallet from existing pool Return Not Found") {
    val notFoundErr = server.mapper.objectMapper.readValue[ErrorResponseBody](
      assertGetWallet(poolName, "not_exist_wallet", Status.NotFound).contentString)
    assert(notFoundErr.rc === ErrorCode.Not_Found)
  }

  test("WalletsApi#Create already exist wallet Return Ok") {
    val createdW = walletFromResponse(assertWalletCreation(poolName, "duplicate_wallet", "bitcoin", Status.Ok))
    assert("duplicate_wallet" === createdW.name)
    assert(Map[String, Any]() === createdW.configuration)
    assert("bitcoin" === createdW.currency.name)
    val createdIgnore = walletFromResponse(assertWalletCreation(poolName, "duplicate_wallet", "bitcoin", Status.Ok))
    assert(createdW === createdIgnore)
  }

  test("WalletsApi#Get two wallets from pool Return OK") {
    walletFromResponse(assertWalletCreation(poolName, "wallet_1", "bitcoin", Status.Ok))
    walletFromResponse(assertWalletCreation(poolName, "wallet_2", "bitcoin", Status.Ok))
    walletFromResponse(assertWalletCreation(poolName, "wallet_3", "bitcoin", Status.Ok))
    val getW1 = walletsFromResponse(assertGetWallets(poolName, 0, 3, Status.Ok))
    assert(getW1.wallets.size === 3)
    val getW2 = walletsFromResponse(assertGetWallets(poolName, 1, 2, Status.Ok))
    assert(getW2.wallets.size === 2)
    assert(!getW2.wallets.exists(_.name == getW1.wallets.head.name))
    assert(getW2.wallets.exists(_.name == getW1.wallets.tail.head.name))
    assert(getW2.wallets.exists(_.name == getW1.wallets.tail.tail.head.name))
  }

  test("WalletsApi#Get wallets with invalid offset and batch size") {
    walletFromResponse(assertWalletCreation(poolName, "wallet_1", "bitcoin", Status.Ok))
    walletFromResponse(assertWalletCreation(poolName, "wallet_2", "bitcoin", Status.Ok))
    walletFromResponse(assertWalletCreation(poolName, "wallet_3", "bitcoin", Status.Ok))
    assertGetWallets(poolName, 1, -2, Status.BadRequest)
    assertGetWallets(poolName, -1, 2, Status.BadRequest)
  }

  test("WalletsApi#Get no wallets from existing pool") {
    val emptyPoolName = "empty_pool"
    createPool(emptyPoolName)
    val result = assertGetWallets(emptyPoolName, 0, 2, Status.Ok)
    assert(WalletsViewWithCount(0, Array[WalletView]()) === walletsFromResponse(result))
    deletePool(emptyPoolName)
  }

  test("WalletsApi#Get/Post wallet(s) from non existing pool") {
    val expectedErr = ErrorResponseBody(ErrorCode.Bad_Request, Map("response"->"Wallet pool doesn't exist", "pool_name"->"not_existing_pool"))
    val result = assertGetWallets("not_existing_pool", 0, 2, Status.BadRequest)
    val getWalletsErr = server.mapper.objectMapper.readValue[ErrorResponseBody](result.contentString)
    assert(getWalletsErr.rc === expectedErr.rc)
    assert(getWalletsErr.msg.getOrElse("error_code", 0) === ErrorCodes.WALLET_POOL_NOT_FOUND)
    val getWalletErr = server.mapper.objectMapper.readValue[ErrorResponseBody](
      assertGetWallet("not_existing_pool", "my_wallet", Status.BadRequest).contentString)
    assert(getWalletErr.rc === expectedErr.rc)
    assert(getWalletErr.msg.getOrElse("error_code", 0) === ErrorCodes.WALLET_POOL_NOT_FOUND)
    val postWalletErr = server.mapper.objectMapper.readValue[ErrorResponseBody](
      assertWalletCreation("not_existing_pool", "my_wallet", "bitcoin", Status.BadRequest).contentString)
    assert(postWalletErr.rc === expectedErr.rc)
    assert(postWalletErr.msg.getOrElse("error_code", 0) === ErrorCodes.WALLET_POOL_NOT_FOUND)
  }

  test("WalletsApi#Post wallet with non exist currency to existing pool") {
    val expectedErr = ErrorResponseBody(ErrorCode.Bad_Request, Map("response"->"Currency not support", "currency_name"->"non_existing_currency"))
    val result = assertWalletCreation(poolName, "my_wallet", "non_existing_currency", Status.BadRequest)
    val postWalletErr = server.mapper.objectMapper.readValue[ErrorResponseBody](result.contentString)
    assert(expectedErr.rc === postWalletErr.rc)
    assert(postWalletErr.msg.getOrElse("error_code", 0) === ErrorCodes.CURRENCY_NOT_FOUND)
  }

  private def walletFromResponse(response: Response): WalletView = parse[WalletView](response)
  private def walletsFromResponse(response: Response): WalletsViewWithCount = parse[WalletsViewWithCount](response)

  private def assertGetWallet(poolName: String, walletName: String, expected: Status): Response = {
    server.httpGet(path = s"/pools/$poolName/wallets/$walletName", andExpect = expected)
  }

  private def assertGetWallets(poolName: String, offset: Int, count: Int, expected: Status): Response = {
    server.httpGet(path = s"/pools/$poolName/wallets?offset=$offset&count=$count", andExpect = expected)
  }
}
