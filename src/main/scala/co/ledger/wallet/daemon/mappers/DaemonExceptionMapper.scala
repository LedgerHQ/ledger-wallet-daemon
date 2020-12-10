package co.ledger.wallet.daemon.mappers

import co.ledger.wallet.daemon.controllers.responses.ResponseSerializer
import co.ledger.wallet.daemon.exceptions._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finatra.http.exceptions.ExceptionMapper
import com.twitter.finatra.http.response.ResponseBuilder
import javax.inject.{Inject, Singleton}

@Singleton
class DaemonExceptionMapper @Inject()(response: ResponseBuilder)
  extends ExceptionMapper[DaemonException] {

  private def daemonExceptionInfo(de: DaemonException): Map[String, Any] =
    Map("response" -> de.msg, "error_code" -> de.code)

  override def toResponse(request: Request, throwable: DaemonException): Response = {
    throwable match {
      case anfe: AccountNotFoundException =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(anfe) + ("account_index" -> anfe.accountIndex),
          response)
      case e: OperationNotFoundException =>
        val next = request.getParam("next")
        val previous = request.getParam("previous")
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(e) + ("next_cursor" -> next, "previous_cursor" -> previous),
          response)
      case wnfe: WalletNotFoundException =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(wnfe) + ("wallet_name" -> wnfe.walletName),
          response)
      case wpnfe: WalletPoolNotFoundException =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(wpnfe) + ("pool_name" -> wpnfe.poolName),
          response)
      case wpaee: WalletPoolAlreadyExistException =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(wpaee) + ("pool_name" -> wpaee.poolName),
          response)
      case cnfe: CurrencyNotFoundException =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(cnfe) + ("currency_name" -> cnfe.currencyName),
          response)
      case cnfe: CurrencyNotSupportedException =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(cnfe) + ("currency_name" -> cnfe.currencyName),
          response)
      case unfe: UserNotFoundException =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(unfe) + ("pub_key" -> unfe.pubKey),
          response)
      case uaee: UserAlreadyExistException =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(uaee) + ("pub_key" -> uaee.pubKey),
          response)
      case iae: CoreBadRequestException =>
        val walletName = request.getParam("wallet_name")
        val poolName = request.getParam("pool_name")
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(iae) + ("pool_name" -> poolName, "wallet_name" -> walletName),
          response)
      case ssnme: SignatureSizeUnmatchException =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(ssnme) + ("tx_size" -> ssnme.txSize, "sig_size" -> ssnme.signatureSize),
          response
        )
      case enfe: ERC20NotFoundException =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(enfe) + ("contract" -> enfe.contract),
          response
        )
      case e: ERC20BalanceNotEnough =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(e) + ("contract" -> e.tokenAddress),
          response
        )
      case e: AmountNotEnoughToActivateAccount =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(e) + ("currency_name" -> e.currency.getName),
          response
        )
      case e: AccountSyncException =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(e),
          response
        )
      case e: DaemonDatabaseException =>
        ResponseSerializer.serializeInternalError(request,
          response, e.t
        )
      case e: InvalidCurrencyForErc20Operation =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(e),
          response
        )
      case e: UnsupportedNativeSegwitException =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(e) + ("currency_name" -> e.currencyName),
          response
        )
      case e: InvalidEIP55Format =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(e),
          response
        )
      case e: SyncOnGoingException =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(e),
          response
        )
      case e: CoreDatabaseException =>
        ResponseSerializer.serializeInternalError(request, response, e)
      case e: FallbackBalanceProviderException =>
        ResponseSerializer.serializeInternalError(request, response, e)
      case e: InvalidUrlException =>
        ResponseSerializer.serializeInternalError(request, response, e)
      case e@ResyncOnGoingException(targetHeight, currentHeight) =>
        ResponseSerializer.serializeBadRequest(request,
          daemonExceptionInfo(e) + ("sync_status_target" -> targetHeight, "sync_status_current" -> currentHeight),
          response
        )
      case e: InvalidErc20OperationsListException =>
        ResponseSerializer.serializeInternalError(request, response, e)
    }
  }
}
