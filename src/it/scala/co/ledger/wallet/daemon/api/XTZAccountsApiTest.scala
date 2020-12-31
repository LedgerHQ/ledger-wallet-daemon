package co.ledger.wallet.daemon.api

import co.ledger.core.TezosOperationTag
import co.ledger.wallet.daemon.controllers.TransactionsController.CreateXTZTransactionRequest
import co.ledger.wallet.daemon.models.coins.UnsignedTezosTransactionView
import co.ledger.wallet.daemon.models.{DelegationView, FreshAddressView}
import co.ledger.wallet.daemon.services.OperationQueryParams
import co.ledger.wallet.daemon.utils.APIFeatureTest
import com.fasterxml.jackson.databind.JsonNode
import com.twitter.finagle.http.Status

class XTZAccountsApiTest extends APIFeatureTest {
  val poolName = "tez_test_pool"

  override def beforeAll(): Unit = {
    createPool(poolName)
  }

  override def afterAll(): Unit = {
    deletePool(poolName)
  }

  private val CORRECT_BODY_XTZ =
    """{
      "account_index": 0,
      "derivations": [
        {
          "owner": "main",
          "path": "44'/1729'/0'",
          "pub_key": "037A8EA0E40DCDD4CA436A00465273EC189F2920B497014DAFA5FA52011E14381F",
          "chain_code": "7D2F5593797A762EA2E8C2594EAC47A20BB9447EEE57223BE2078A0DE125C9A8"
        }
      ]
    }"""

  test("Create XTZ account") {
    val walletName = "xtzWalletAccountCreation"
    assertWalletCreation(poolName, walletName, "tezos", Status.Ok)
    assertCreateAccount(CORRECT_BODY_XTZ, poolName, walletName, Status.Ok)
    val addresses = parse[Seq[FreshAddressView]](assertGetFreshAddresses(poolName, walletName, index = 0, Status.Ok))
    assert(addresses.nonEmpty)
    info(s"Here are addresses : $addresses")
    assertSyncAccount(poolName, walletName, 0)
    val operations = parse[Map[String, JsonNode]](assertGetAccountOps(poolName, walletName, 0, OperationQueryParams(None, None, 1000, 0), Status.Ok))
    assert(operations.nonEmpty)
  }

  test("Create XTZ Transaction with no fees") {
    val walletName = "xtzWalletForCreateTX"
    assertWalletCreation(poolName, walletName, "tezos", Status.Ok)
    assertCreateAccount(CORRECT_BODY_XTZ, poolName, walletName, Status.Ok)
    val addresses = parse[Seq[FreshAddressView]](assertGetFreshAddresses(poolName, walletName, index = 0, Status.Ok))
    assert(addresses.nonEmpty)
    info(s"Here are addresses : $addresses")
    assertSyncAccount(poolName, walletName, 0)
    val operations = parse[Map[String, JsonNode]](assertGetAccountOps(poolName, walletName, 0, OperationQueryParams(None, None, 1000, 0), Status.Ok))
    assert(operations.nonEmpty)

    // No fees, no feesLevel provided
    val add = addresses.head.address
    val receiverAddress = "tz2LLBZYevBRjNBvzJ24GbAkJ5bNFDQi3KQv"

    val txBadRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_DELEGATION, receiverAddress,
                                                   "1", false, None, None, None, None)
    val txBadRequestJson = server.mapper.writeValueAsString(txBadRequest)
    // Neither fees nor fees_level has been provided, expect failure due to MethodValidation check
    assertCreateTransaction(txBadRequestJson, poolName, walletName, 0, Status.BadRequest)
  }

  test("Create XTZ Transaction with not enough funds") {
    val walletName = "xtzWalletForCreateTX"
    assertWalletCreation(poolName, walletName, "tezos", Status.Ok)
    assertCreateAccount(CORRECT_BODY_XTZ, poolName, walletName, Status.Ok)
    val addresses = parse[Seq[FreshAddressView]](assertGetFreshAddresses(poolName, walletName, index = 0, Status.Ok))
    assert(addresses.nonEmpty)
    info(s"Here are addresses : $addresses")
    assertSyncAccount(poolName, walletName, 0)
    val operations = parse[Map[String, JsonNode]](assertGetAccountOps(poolName, walletName, 0, OperationQueryParams(None, None, 1000, 0), Status.Ok))
    assert(operations.nonEmpty)

    val add = addresses.head.address
    val receiverAddress = "tz2LLBZYevBRjNBvzJ24GbAkJ5bNFDQi3KQv"

    // Not enough funds error
    val txTooPoorRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_TRANSACTION, receiverAddress,
                                                       "80000000", false, Some("800"), Some("800"), None, Some("FAST"))
    val txTooPoorRequestJson = server.mapper.writeValueAsString(txTooPoorRequest)
    assertCreateTransaction(txTooPoorRequestJson, poolName, walletName, 0, Status.BadRequest)

    // Check No fees amount provided with fee_level provided
    val txRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_TRANSACTION, receiverAddress,
                                                "1", false, Some("8"), Some("8"), None, Some("SLOW"))
    val txRequestJson = server.mapper.writeValueAsString(txRequest)
    val transactionView = parse[UnsignedTezosTransactionView](assertCreateTransaction(txRequestJson, poolName, walletName, 0, Status.Ok))
    info(s"Here is transaction view : $transactionView")
    assert(transactionView.operationType == TezosOperationTag.OPERATION_TAG_TRANSACTION)
    assert(transactionView.value.contains("1"))
    assert(transactionView.signing_pubkey == "037A8EA0E40DCDD4CA436A00465273EC189F2920B497014DAFA5FA52011E14381F")
    assert(transactionView.receiver.contains(receiverAddress))
    assert(transactionView.sender == add)

    // Check normal speed fees multiplier
    val txNormalSpeedRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_TRANSACTION, receiverAddress,
                                                           "1", false, Some("8"), Some("8"), Some("322"), Some("NORMAL"))
    val txNormalSpeedRequestJson = server.mapper.writeValueAsString(txNormalSpeedRequest)
    val normalSpeedTransactionView = parse[UnsignedTezosTransactionView](assertCreateTransaction(txNormalSpeedRequestJson, poolName, walletName, 0, Status.Ok))
    info(s"Here is transaction view : $normalSpeedTransactionView")
    assert(normalSpeedTransactionView.fees.contains("644"))

    // Check fast speed multiplier
    val txFastSpeedRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_TRANSACTION, receiverAddress,
                                                         "1", false, Some("8"), Some("8"), Some("100"), Some("FAST"))
    val txFastSpeedRequestJson = server.mapper.writeValueAsString(txFastSpeedRequest)
    val fastSpeedTransactionView = parse[UnsignedTezosTransactionView](assertCreateTransaction(txFastSpeedRequestJson, poolName, walletName, 0, Status.Ok))
    info(s"Here is transaction view : $fastSpeedTransactionView")
    assert(fastSpeedTransactionView.fees.contains("200"))
  }

  test("Create XTZ Transaction with fee levels") {
    val walletName = "xtzWalletForCreateTX"
    assertWalletCreation(poolName, walletName, "tezos", Status.Ok)
    assertCreateAccount(CORRECT_BODY_XTZ, poolName, walletName, Status.Ok)
    val addresses = parse[Seq[FreshAddressView]](assertGetFreshAddresses(poolName, walletName, index = 0, Status.Ok))
    assert(addresses.nonEmpty)
    info(s"Here are addresses : $addresses")
    assertSyncAccount(poolName, walletName, 0)
    val operations = parse[Map[String, JsonNode]](assertGetAccountOps(poolName, walletName, 0, OperationQueryParams(None, None, 1000, 0), Status.Ok))
    assert(operations.nonEmpty)

    val add = addresses.head.address
    val receiverAddress = "tz2LLBZYevBRjNBvzJ24GbAkJ5bNFDQi3KQv"

    // Check No fees amount provided with fee_level provided
    val txRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_TRANSACTION, receiverAddress,
                                                "1", false, Some("8"), Some("8"), None, Some("SLOW"))
    val txRequestJson = server.mapper.writeValueAsString(txRequest)
    val transactionView = parse[UnsignedTezosTransactionView](assertCreateTransaction(txRequestJson, poolName, walletName, 0, Status.Ok))
    info(s"Here is transaction view : $transactionView")
    assert(transactionView.operationType == TezosOperationTag.OPERATION_TAG_TRANSACTION)
    assert(transactionView.value.contains("1"))
    assert(transactionView.signing_pubkey == "037A8EA0E40DCDD4CA436A00465273EC189F2920B497014DAFA5FA52011E14381F")
    assert(transactionView.receiver.contains(receiverAddress))
    assert(transactionView.sender == add)

    // Check normal speed fees multiplier
    val txNormalSpeedRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_TRANSACTION, receiverAddress,
                                                           "1", false, Some("8"), Some("8"), Some("322"), Some("NORMAL"))
    val txNormalSpeedRequestJson = server.mapper.writeValueAsString(txNormalSpeedRequest)
    val normalSpeedTransactionView = parse[UnsignedTezosTransactionView](assertCreateTransaction(txNormalSpeedRequestJson, poolName, walletName, 0, Status.Ok))
    info(s"Here is transaction view : $normalSpeedTransactionView")
    assert(normalSpeedTransactionView.fees.contains("644"))

    // Check fast speed multiplier
    val txFastSpeedRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_TRANSACTION, receiverAddress,
                                                         "1", false, Some("8"), Some("8"), Some("100"), Some("FAST"))
    val txFastSpeedRequestJson = server.mapper.writeValueAsString(txFastSpeedRequest)
    val fastSpeedTransactionView = parse[UnsignedTezosTransactionView](assertCreateTransaction(txFastSpeedRequestJson, poolName, walletName, 0, Status.Ok))
    info(s"Here is transaction view : $fastSpeedTransactionView")
    assert(fastSpeedTransactionView.fees.contains("200"))
  }

  test("Create XTZ delegation") {
    val walletName = "xtzWalletForDelegateTx"
    assertWalletCreation(poolName, walletName, "tezos", Status.Ok)
    assertCreateAccount(CORRECT_BODY_XTZ, poolName, walletName, Status.Ok)
    val addresses = parse[Seq[FreshAddressView]](assertGetFreshAddresses(poolName, walletName, index = 0, Status.Ok))
    assert(addresses.nonEmpty)
    info(s"Here are addresses : $addresses")
    assertSyncAccount(poolName, walletName, 0)
    val operations = parse[Map[String, JsonNode]](assertGetAccountOps(poolName, walletName, 0, OperationQueryParams(None, None, 1000, 0), Status.Ok))
    assert(operations.nonEmpty)
    val validatorAddress = "tz1YhNsiRRU8aHNGg7NK3uuP6UDAyacJernB"
    // Create delegation transaction
    val txNormalSpeedRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_DELEGATION, validatorAddress, "0", true, Some("100"), Some("100"), Some("100"), Some("NORMAL"))

    val txNormalSpeedRequestJson = server.mapper.writeValueAsString(txNormalSpeedRequest)
    val normalSpeedTransactionView = parse[UnsignedTezosTransactionView](assertCreateTransaction(txNormalSpeedRequestJson, poolName, walletName, 0, Status.Ok))
    info(s"Here is transaction view : $normalSpeedTransactionView")
    assert(normalSpeedTransactionView.fees.contains("200"))
    assert(normalSpeedTransactionView.operationType == TezosOperationTag.OPERATION_TAG_DELEGATION)
  }

  test("Create XTZ undelegation") {
    val walletName = "xtzWalletForUndelegateTx"
    assertWalletCreation(poolName, walletName, "tezos", Status.Ok)
    assertCreateAccount(CORRECT_BODY_XTZ, poolName, walletName, Status.Ok)
    val addresses = parse[Seq[FreshAddressView]](assertGetFreshAddresses(poolName, walletName, index = 0, Status.Ok))
    assert(addresses.nonEmpty)
    info(s"Here are addresses : $addresses")
    assertSyncAccount(poolName, walletName, 0)
    val operations = parse[Map[String, JsonNode]](assertGetAccountOps(poolName, walletName, 0, OperationQueryParams(None, None, 1000, 0), Status.Ok))
    assert(operations.nonEmpty)
    // Create undelegation transaction
    val txNormalSpeedRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_DELEGATION, "", "0", true, Some("100"), Some("100"), Some("100"), Some("NORMAL"))

    val txNormalSpeedRequestJson = server.mapper.writeValueAsString(txNormalSpeedRequest)
    val normalSpeedTransactionView = parse[UnsignedTezosTransactionView](assertCreateTransaction(txNormalSpeedRequestJson, poolName, walletName, 0, Status.Ok))
    info(s"Here is transaction view : $normalSpeedTransactionView")
    assert(normalSpeedTransactionView.fees.contains("200"))
    assert(normalSpeedTransactionView.operationType == TezosOperationTag.OPERATION_TAG_DELEGATION)
  }

  test("Get XTZ empty current delegation") {
    val walletName = "xtzWalletForEmptyCurrentDelegation"
    assertWalletCreation(poolName, walletName, "tezos", Status.Ok)
    assertCreateAccount(CORRECT_BODY_XTZ, poolName, walletName, Status.Ok)
    val addresses = parse[Seq[FreshAddressView]](assertGetFreshAddresses(poolName, walletName, index = 0, Status.Ok))
    assert(addresses.nonEmpty)
    info(s"Here are addresses : $addresses")
    assertSyncAccount(poolName, walletName, 0)
    val operations = parse[Map[String, JsonNode]](assertGetAccountOps(poolName, walletName, 0, OperationQueryParams(None, None, 1000, 0), Status.Ok))
    assert(operations.nonEmpty)

    val delegations = parse[Seq[DelegationView]](assertGetAccountDelegation(poolName, walletName, 0, Status.Ok))
    info(s"Here is delegation view : $delegations")
    assert(delegations.isEmpty)
  }

  ignore("Create XTZ transaction to self") {
    val walletName = "xtzWalletForTxToSelf"
    assertWalletCreation(poolName, walletName, "tezos", Status.Ok)
    assertCreateAccount(CORRECT_BODY_XTZ, poolName, walletName, Status.Ok)
    val addresses = parse[Seq[FreshAddressView]](assertGetFreshAddresses(poolName, walletName, index = 0, Status.Ok))
    assert(addresses.nonEmpty)
    assertSyncAccount(poolName, walletName, 0)
    val operations = parse[Map[String, JsonNode]](assertGetAccountOps(poolName, walletName, 0, OperationQueryParams(None, None, 1000, 0), Status.Ok))
    assert(operations.nonEmpty)
    // Create transaction to self
    val txNormalSpeedRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_TRANSACTION, addresses.head.address, "100", false, Some("800"), Some("800"), Some("322"), Some("NORMAL"))

    val txNormalSpeedRequestJson = server.mapper.writeValueAsString(txNormalSpeedRequest)
    // FIXME: Should be Status.BadRequest
    val normalSpeedTransactionView = parse[UnsignedTezosTransactionView](assertCreateTransaction(txNormalSpeedRequestJson, poolName, walletName, 0, Status.InternalServerError))
    info(s"Here is transaction view : $normalSpeedTransactionView")
    assert(normalSpeedTransactionView.fees.contains("322"))
    assert(normalSpeedTransactionView.operationType == TezosOperationTag.OPERATION_TAG_TRANSACTION)
  }
}
