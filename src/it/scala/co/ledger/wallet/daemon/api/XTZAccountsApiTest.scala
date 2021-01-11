package co.ledger.wallet.daemon.api

import java.time.{LocalDateTime, ZoneOffset}
import java.util.Date

import co.ledger.core.{OperationType, TezosOperationTag, WalletType}
import co.ledger.wallet.daemon.controllers.TransactionsController.CreateXTZTransactionRequest
import co.ledger.wallet.daemon.models.Operations.PackedOperationsView
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

  // Because this is an un-revealed account, all tx fees will be "doubled" (fees for reveal + fees for tx)
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
    val packedOperations = parse[PackedOperationsView](assertGetAccountOps(poolName, walletName, 0, OperationQueryParams(None, None, 1000, 0), Status.Ok))
    assert(packedOperations.operations.nonEmpty)
    val operation = packedOperations.operations.last
    assert(operation.uid == "56e3ec77cb60544438e1b064abc46e54b83c7c1164fe28836ef897df41b5ccf9")
    assert(operation.currencyName == "tezos")
    assert(operation.currencyFamily == WalletType.TEZOS)
    assert(operation.confirmations > 0)
    val time = LocalDateTime.parse("2020-11-25T11:42:32")
    val instant = time.atZone(ZoneOffset.UTC).toInstant
    val date = Date.from(instant)
    assert(operation.time == date)
    assert(operation.blockHeight.contains(1230589L))
    assert(operation.opType == OperationType.RECEIVE)
    assert(operation.amount == "100000")
    assert(operation.fees == "76631")
    assert(operation.walletName == "xtzWalletAccountCreation")
    assert(operation.accountIndex == 0)
    assert(operation.senders.contains("tz2RLDqnB4pQQZKCpqBusHmiMMHB1LNWcT7W"))
    assert(operation.recipients.contains("tz2BFCee4VSARxdc6Tv7asSiYZBF957e4cwd"))
    assert(operation.recipients.contains("tz2BFCee4VSARxdc6Tv7asSiYZBF957e4cwd"))
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

    val add = addresses.head.address

    // No fees, no feesLevel provided
    val receiverAddress = "tz2LLBZYevBRjNBvzJ24GbAkJ5bNFDQi3KQv"

    val txBadRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_DELEGATION, receiverAddress,
      "1", wipeToAddress = false, None, None, None, None)
    val txBadRequestJson = server.mapper.writeValueAsString(txBadRequest)
    // Neither fees nor fees_level has been provided, expect failure due to MethodValidation check
    assertCreateTransaction(txBadRequestJson, poolName, walletName, 0, Status.BadRequest)

    // Check No fees amount provided with fee_level provided
    val txRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_TRANSACTION, receiverAddress,
      "1", wipeToAddress = false, Some("8"), Some("8"), None, Some("SLOW"))
    val txRequestJson = server.mapper.writeValueAsString(txRequest)
    val transactionView = parse[UnsignedTezosTransactionView](assertCreateTransaction(txRequestJson, poolName, walletName, 0, Status.Ok))
    info(s"Here is transaction view : $transactionView")
    assert(transactionView.operationType == TezosOperationTag.OPERATION_TAG_TRANSACTION)
    assert(transactionView.value.contains("1"))
    assert(transactionView.signing_pubkey == "037A8EA0E40DCDD4CA436A00465273EC189F2920B497014DAFA5FA52011E14381F")
    assert(transactionView.receiver.contains(receiverAddress))
    assert(transactionView.sender == add)
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

    val receiverAddress = "tz2LLBZYevBRjNBvzJ24GbAkJ5bNFDQi3KQv"

    // Not enough funds error
    val txTooPoorRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_TRANSACTION, receiverAddress,
      "80000000", wipeToAddress = false, Some("800"), Some("800"), None, Some("FAST"))
    val txTooPoorRequestJson = server.mapper.writeValueAsString(txTooPoorRequest)
    assertCreateTransaction(txTooPoorRequestJson, poolName, walletName, 0, Status.BadRequest)
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

    val receiverAddress = "tz2LLBZYevBRjNBvzJ24GbAkJ5bNFDQi3KQv"

    // Check slow speed fees multiplier
    val txSlowSpeedRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_TRANSACTION, receiverAddress,
      "1", wipeToAddress = false, Some("8"), Some("8"), Some("100"), Some("SLOW"))
    val txSlowSpeedRequestJson = server.mapper.writeValueAsString(txSlowSpeedRequest)
    val slowSpeedTransactionView = parse[UnsignedTezosTransactionView](assertCreateTransaction(txSlowSpeedRequestJson, poolName, walletName, 0, Status.Ok))
    info(s"Here is transaction view : $slowSpeedTransactionView")
    assert(slowSpeedTransactionView.fees.contains("150"))

    // Check normal speed fees multiplier
    val txNormalSpeedRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_TRANSACTION, receiverAddress,
      "1", wipeToAddress = false, Some("8"), Some("8"), Some("100"), Some("NORMAL"))
    val txNormalSpeedRequestJson = server.mapper.writeValueAsString(txNormalSpeedRequest)
    val normalSpeedTransactionView = parse[UnsignedTezosTransactionView](assertCreateTransaction(txNormalSpeedRequestJson, poolName, walletName, 0, Status.Ok))
    info(s"Here is transaction view : $normalSpeedTransactionView")
    assert(normalSpeedTransactionView.fees.contains("200"))

    // Check fast speed multiplier
    val txFastSpeedRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_TRANSACTION, receiverAddress,
      "1", wipeToAddress = false, Some("8"), Some("8"), Some("100"), Some("FAST"))
    val txFastSpeedRequestJson = server.mapper.writeValueAsString(txFastSpeedRequest)
    val fastSpeedTransactionView = parse[UnsignedTezosTransactionView](assertCreateTransaction(txFastSpeedRequestJson, poolName, walletName, 0, Status.Ok))
    info(s"Here is transaction view : $fastSpeedTransactionView")
    assert(fastSpeedTransactionView.fees.contains("250"))
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
    val txNormalSpeedRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_DELEGATION, validatorAddress, "0", wipeToAddress = true, Some("100"), Some("100"), Some("100"), Some("NORMAL"))

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
    val txNormalSpeedRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_DELEGATION, "", "0", wipeToAddress = true, Some("100"), Some("100"), Some("100"), Some("NORMAL"))

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

  test("Create XTZ transaction to self") {
    val walletName = "xtzWalletForTxToSelf"
    assertWalletCreation(poolName, walletName, "tezos", Status.Ok)
    assertCreateAccount(CORRECT_BODY_XTZ, poolName, walletName, Status.Ok)
    val addresses = parse[Seq[FreshAddressView]](assertGetFreshAddresses(poolName, walletName, index = 0, Status.Ok))
    assert(addresses.nonEmpty)
    assertSyncAccount(poolName, walletName, 0)
    val operations = parse[Map[String, JsonNode]](assertGetAccountOps(poolName, walletName, 0, OperationQueryParams(None, None, 1000, 0), Status.Ok))
    assert(operations.nonEmpty)
    // Create transaction to self
    val txNormalSpeedRequest = CreateXTZTransactionRequest(TezosOperationTag.OPERATION_TAG_TRANSACTION, addresses.head.address, "100", wipeToAddress = false, Some("800"), Some("800"), Some("322"), Some("NORMAL"))

    val txNormalSpeedRequestJson = server.mapper.writeValueAsString(txNormalSpeedRequest)
    // FIXME: Should be Status.BadRequest
    val normalSpeedTransactionView = parse[UnsignedTezosTransactionView](assertCreateTransaction(txNormalSpeedRequestJson, poolName, walletName, 0, Status.InternalServerError))
    info(s"Here is transaction view : $normalSpeedTransactionView")
    assert(normalSpeedTransactionView.fees.contains("322"))
    assert(normalSpeedTransactionView.operationType == TezosOperationTag.OPERATION_TAG_TRANSACTION)
  }
}
