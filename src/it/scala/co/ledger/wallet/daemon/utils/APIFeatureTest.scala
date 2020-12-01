package co.ledger.wallet.daemon.utils

import co.ledger.wallet.daemon.ServerImpl
import co.ledger.wallet.daemon.services.OperationQueryParams
import com.twitter.finagle.http.{Response, Status}
import com.twitter.finatra.http.EmbeddedHttpServer
import com.twitter.inject.Mockito
import com.twitter.inject.server.FeatureTest

trait APIFeatureTest extends FeatureTest with Mockito {
  val serverImpl = new ServerImpl
  override val server: EmbeddedHttpServer = new EmbeddedHttpServer(serverImpl)

  def parse[A](response: Response)(implicit manifest: Manifest[A]): A = server.mapper.parse[A](response)

  def assertWalletCreation(poolName: String, walletName: String, currencyName: String, expected: Status): Response = {
    server.httpPost(path = s"/pools/$poolName/wallets",
      postBody = s"""{\"currency_name\":\"$currencyName\",\"wallet_name\":\"$walletName\"}""",
      andExpect = expected)
  }

  def assertWalletNativeSegwitCreation(poolName: String, walletName: String, currencyName: String, expected: Status): Response = {
    server.httpPost(path = s"/pools/$poolName/wallets",
      postBody = s"""{\"currency_name\":\"$currencyName\",\"wallet_name\":\"$walletName\",\"is_native_segwit\":true}""",
      andExpect = expected)
  }

  def getPools(): Response = {
    server.httpGet("/pools", andExpect = Status.Ok)
  }

  def getPool(poolName: String): Response = {
    getPool(poolName, Status.Ok)
  }

  def getPool(poolName: String, expected: Status): Response = {
    server.httpGet(s"/pools/$poolName", andExpect = expected)
  }

  def createPool(poolName: String, expected: Status = Status.Ok): Response = {
    server.httpPost("/pools", s"""{"pool_name":"$poolName"}""", andExpect = expected)
  }

  def getAddresses(poolName: String, walletName: String, account: Int, from: Int, to: Int, expected: Status = Status.Ok): Response = {
    server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts/$account/addresses?from=$from&to=$to", andExpect = expected)
  }

  def deletePool(poolName: String): Response = {
    server.httpDelete(s"/pools/$poolName", "", andExpect = Status.Ok)
  }

  def deletePoolIfExists(poolName: String): Response = {
    // No expected status
    server.httpDelete(s"/pools/$poolName", "")
  }

  def assertSyncPools(expected: Status): Response = {
    server.httpPost("/pools/operations/synchronize", "", andExpect = expected)
  }

  def assertSyncAccount(poolName: String, walletName: String, accIdx: Int): Response = {
    val resp = server.httpPost(s"/pools/$poolName/wallets/$walletName/accounts/$accIdx/operations/synchronize", "", andExpect = Status.Ok)
    resp
  }

  protected def assertCreateAccount(accountCreationBody: String, poolName: String, walletName: String, expected: Status): Response = {
    server.httpPost(s"/pools/$poolName/wallets/$walletName/accounts", accountCreationBody, andExpect = expected)
  }

  protected def assertCreateAccountExtended(accountCreationBody: String, poolName: String, walletName: String, expected: Status): Response = {
    server.httpPost(s"/pools/$poolName/wallets/$walletName/accounts/extended", accountCreationBody, andExpect = expected)
  }

  protected def clearAccount(poolName: String, walletName: String, accountIdx: Int, expected: Status): Response = {
    server.httpPost(s"/pools/$poolName/wallets/$walletName/accounts/$accountIdx/resync", "", andExpect = expected)
  }

  protected def assertGetAccountOp(poolName: String, walletName: String, accountIndex: Int, uid: String, fullOp: Int, expected: Status): Response = {
    val sb = new StringBuilder(s"/pools/$poolName/wallets/$walletName/accounts/$accountIndex/operations/$uid?full_op=$fullOp")
    server.httpGet(sb.toString(), andExpect = expected)
  }

  protected def assertGetFirstOperation(index: Int, poolName: String, walletName: String, expected: Status): Response = {
    server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts/$index/operations/first", andExpect = expected)
  }

  protected def history(poolName: String, walletName: String, accountIndex: Int, start: String, end: String, timeInterval: String, expected: Status): Response = {
    server.httpGet(
      path = s"/pools/$poolName/wallets/$walletName/accounts/$accountIndex/history?start=$start&end=$end&time_interval=$timeInterval",
      andExpect = expected)
  }

  protected def assertGetAccountOps(poolName: String, walletName: String, accountIndex: Int, params: OperationQueryParams, expected: Status): Response = {
    val sb = new StringBuilder(s"/pools/$poolName/wallets/$walletName/accounts/$accountIndex/operations?")
    params.previous.foreach { p =>
      sb.append("previous=" + p.toString + "&")
    }
    params.next.foreach { n =>
      sb.append("next=" + n.toString + "&")
    }
    sb.append(s"batch=${params.batch}&full_op=${params.fullOp}")
    server.httpGet(sb.toString(), andExpect = expected)
  }

  protected def assertGetAccounts(index: Option[Int], poolName: String, walletName: String, expected: Status): Response = {
    index match {
      case None => server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts", andExpect = expected)
      case Some(i) => server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts/$i", andExpect = expected)
    }
  }

  protected def assertGetAccountCreationInfo(poolName: String, walletName: String, index: Option[Int], expected: Status): Response = {
    index match {
      case None => server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts/next", andExpect = expected)
      case Some(i) => server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts/next?account_index=$i", andExpect = expected)
    }
  }

  protected def assertGetFreshAddresses(poolName: String, walletName: String, index: Int, expected: Status): Response = {
    server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts/$index/addresses/fresh", andExpect = expected)
  }

  protected def assertGetUTXO(poolName: String, walletName: String, index: Int, expected: Status): Response = {
    server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts/$index/utxo", andExpect = expected)
    server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts/$index/utxo?offset=2&batch=10", andExpect = expected)
  }

  protected def assertSignTransaction(tx: String, poolName: String, walletName: String, accountIndex: Int, expected: Status): Response = {
    server.httpPost(
      s"/pools/$poolName/wallets/$walletName/accounts/$accountIndex/transactions/sign",
      tx,
      andExpect = expected
    )
  }

  protected def assertGetAccount(poolName: String, walletName: String, accountIndex: Int, expected: Status): Response = {
    server.httpGet(
      s"/pools/$poolName/wallets/$walletName/accounts/$accountIndex",
      andExpect = expected
    )
  }

  protected def assertGetAccountDelegation(poolName: String, walletName: String, accountIndex: Int, expected: Status): Response = {
    server.httpGet(
      s"/pools/$poolName/wallets/$walletName/accounts/$accountIndex/delegations",
      andExpect = expected
    )
  }

  protected def assertCreateTransaction(tx: String, poolName: String, walletName: String, accountIndex: Int, expected: Status): Response = {
    server.httpPost(
      s"/pools/$poolName/wallets/$walletName/accounts/$accountIndex/transactions",
      tx,
      andExpect = expected
    )
  }

  override def afterAll(): scala.Unit = {
    super.afterAll()
    server.close()
  }
}
