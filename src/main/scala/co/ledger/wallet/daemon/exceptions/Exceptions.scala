package co.ledger.wallet.daemon.exceptions

import java.util.UUID

import collection.JavaConverters._
import co.ledger.core.{Amount, Currency}

case class ERC20NotFoundException(contract: String) extends {
  val msg = s"No ERC20 token $contract in your account"
  val code = ErrorCodes.ERC20_NOT_FOUND
} with DaemonException(msg)

case class ERC20BalanceNotEnough(tokenAddress: String, balance: BigInt, need: BigInt)
  extends {
    val msg = s"Not enough funds on ERC20 ($tokenAddress) account: having $balance, need $need"
    val code = ErrorCodes.ERC20_BALANCE_NOT_ENOUGH
  } with DaemonException(msg)

case class AmountNotEnoughToActivateAccount(currency: Currency, minimalAmount: Amount)
  extends {
    private val currencyUnit = currency.getUnits.asScala.drop(1).head
    private val formattedMinimalAmount = minimalAmount.toUnit(currencyUnit).toLong
    val msg = s"Recipient address is inactive. Send at least $formattedMinimalAmount ${currencyUnit.getCode} to activate it"
    val code = ErrorCodes.AMOUNT_NOT_ENOUGH_TO_ACTIVATE_ACCOUNT
  } with DaemonException(msg)

case class InvalidEIP55Format(address: String)
  extends {
    val msg = s"Invalid address : Invalid EIP55 format $address"
    val code = ErrorCodes.CORE_BAD_REQUEST
  } with DaemonException(msg)

case class UnsupportedNativeSegwitException(currencyName: String)
  extends {
    val msg = s"Native SegWit is not supported for currency $currencyName"
    val code = ErrorCodes.INVALID_CURRENCY_FOR_NATIVE_SEGWIT
  } with DaemonException(msg)

case class AccountNotFoundException(accountIndex: Int) extends {
  val msg = s"Account with index $accountIndex doesn't exist"
  val code = ErrorCodes.ACCOUNT_NOT_FOUND
} with DaemonException(msg)

case class OperationNotFoundException(cursor: UUID) extends {
  val msg = s"Operation with previous or next cursor $cursor doesn't exist"
  val code = ErrorCodes.OPERATION_NOT_FOUND
} with DaemonException(msg)

case class WalletNotFoundException(walletName: String) extends {
  val msg = s"Wallet $walletName doesn't exist"
  val code = ErrorCodes.WALLET_NOT_FOUND
} with DaemonException(msg)

case class WalletPoolNotFoundException(poolName: String) extends {
  val msg = s"Wallet pool $poolName doesn't exist"
  val code = ErrorCodes.WALLET_POOL_NOT_FOUND
} with DaemonException(msg)

case class WalletPoolAlreadyExistException(poolName: String) extends {
  val msg = s"Wallet pool $poolName already exists"
  val code = ErrorCodes.WALLET_POOL_ALREADY_EXIST
} with DaemonException(msg)

case class FallbackBalanceProviderException(currency: String, host: String, query: String) extends {
  val msg = s"Failed to retrieve balance for $currency currency on host= $host with query= $query"
  val code = ErrorCodes.PROVIDER_BALANCE_EXCEPTION
} with DaemonException(msg)

case class CurrencyNotFoundException(currencyName: String) extends {
  val msg = s"Currency $currencyName is not found"
  val code = ErrorCodes.CURRENCY_NOT_FOUND
} with DaemonException(msg)

case class CurrencyNotSupportedException(currencyName: String) extends {
  val msg = s"Currency $currencyName is not supported"
  val code = ErrorCodes.CURRENCY_NOT_SUPPORTED
} with DaemonException(msg)

case class UserNotFoundException(pubKey: String) extends {
  val msg = s"User $pubKey doesn't exist"
  val code = ErrorCodes.USER_NOT_FOUND
} with DaemonException(msg)

case class UserAlreadyExistException(pubKey: String) extends {
  val msg = s"User $pubKey already exists"
  val code = ErrorCodes.USER_ALREADY_EXIST
} with DaemonException(msg)

case class InvalidCurrencyForErc20Operation() extends {
  val msg = "ERC20 Operation with non ETH account"
  val code = ErrorCodes.INVALID_CURRENCY_FOR_ERC20
} with DaemonException(msg)

case class CoreBadRequestException(msg: String, t: Throwable) extends DaemonException(msg, t) {
  def code: Int = ErrorCodes.CORE_BAD_REQUEST
}

case class DaemonDatabaseException(msg: String, t: Throwable) extends DaemonException(msg, t) {
  def code: Int = ErrorCodes.DAEMON_DATABASE_EXCEPTION
}

case class CoreDatabaseException(msg: String, t: Throwable = null) extends DaemonException(msg, t) {
  def code: Int = ErrorCodes.CORE_DATABASE_EXCEPTION
}

case class AccountSyncException(poolName: String, walletName: String, accountIndex: Int, t: Throwable) extends {
  val msg = s"Synchronization of Account $poolName:$walletName:$accountIndex failed"
  val code = ErrorCodes.ACCOUNT_SYNC_FAILED
} with DaemonException(msg, t)

case class SyncOnGoingException() extends {
  val msg = "Synchronization is on going ..."
  val code = ErrorCodes.SYNC_ON_GOING
} with DaemonException(msg)

case class ResyncOnGoingException(targetHeight: Long, currentHeight: Long) extends {
  val msg = "Account is under resynchronization"
  val code = ErrorCodes.RESYNC_ON_GOING
} with DaemonException(msg)

case class SignatureSizeUnmatchException(txSize: Int, signatureSize: Int) extends {
  val code = ErrorCodes.SIGNATURE_SIZE_UNMATCH
  val msg = "Signatures and transaction inputs size not matching"
} with DaemonException(msg)

case class InvalidUrlException(msg: String) extends {
  val code = ErrorCodes.INVALID_URL_EXCEPTION
} with DaemonException(msg)

case class InvalidErc20OperationsListException() extends {
  val code = ErrorCodes.INVALID_ERC20_OPERATIONS_LIST_EXCEPTION
  val msg = "Invalid list of operations received from the libcore. Operations will not be published."
} with DaemonException(msg)

sealed abstract class DaemonException(msg: String, t: Throwable = null) extends Exception(msg, t) {
  def code: Int
  def msg: String
}

object ErrorCodes {
  val SIGNATURE_SIZE_UNMATCH = 101
  val ERC20_BALANCE_NOT_ENOUGH = 102
  val AMOUNT_NOT_ENOUGH_TO_ACTIVATE_ACCOUNT = 103
  val ERC20_NOT_FOUND = 200
  val ACCOUNT_NOT_FOUND = 201
  val OPERATION_NOT_FOUND = 202
  val WALLET_NOT_FOUND = 203
  val WALLET_POOL_NOT_FOUND = 204
  val WALLET_POOL_ALREADY_EXIST = 205
  val CURRENCY_NOT_FOUND = 206
  val USER_NOT_FOUND = 207
  val USER_ALREADY_EXIST = 208
  val CURRENCY_NOT_SUPPORTED = 209
  val INVALID_CURRENCY_FOR_ERC20 = 210
  val ACCOUNT_SYNC_FAILED = 211
  val SYNC_ON_GOING = 212
  val INVALID_CURRENCY_FOR_NATIVE_SEGWIT = 213
  val RESYNC_ON_GOING = 214
  val CORE_BAD_REQUEST = 301
  val DAEMON_DATABASE_EXCEPTION = 302
  val PROVIDER_BALANCE_EXCEPTION = 303
  val CORE_DATABASE_EXCEPTION = 304
  val INVALID_URL_EXCEPTION = 305
  val INVALID_ERC20_OPERATIONS_LIST_EXCEPTION = 306
}
