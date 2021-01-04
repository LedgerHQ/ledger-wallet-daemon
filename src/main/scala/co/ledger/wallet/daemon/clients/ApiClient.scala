package co.ledger.wallet.daemon.clients

import java.net.URL

import co.ledger.core
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.configurations.DaemonConfiguration._
import co.ledger.wallet.daemon.models.FeeMethod
import co.ledger.wallet.daemon.utils.Utils._
import co.ledger.wallet.daemon.utils.{HexUtils, NetUtils}
import com.fasterxml.jackson.annotation.JsonProperty
import com.twitter.finagle.http.{Method, Request}
import com.twitter.finatra.json.FinatraObjectMapper
import com.twitter.inject.Logging
import io.circe.Json
import javax.inject.Singleton

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

// TODO: Map response from service to be more readable
@Singleton
class ApiClient(implicit val ec: ExecutionContext) extends Logging {

  import ApiClient._

  case class CurrencyServiceURL(url: URL, fallback: Option[URL])

  private implicit def urlToHost(url: URL): NetUtils.Host = NetUtils.urlToHost(url)

  private val currencyServiceUrl: Map[String, CurrencyServiceURL] = DaemonConfiguration.explorer.api.paths.map { case (currency, path) => currency ->
    CurrencyServiceURL(new URL(s"${path.host}:${path.port}"), path.fallback.map(new URL(_)))
  }

  private val mapper: FinatraObjectMapper = FinatraObjectMapper.create()

  def fallbackService(currency: String): Option[(URL, ScalaHttpClientPool)] =
    currencyServiceURLFor(currency).fallback.map(url => (url, fallbackServices))

  def getFees(currencyName: String): Future[FeeInfo] = {
    val serviceUrl: CurrencyServiceURL = currencyServiceURLFor(currencyName)
    val path = feesPathForCurrency(currencyName)
    val request = Request(Method.Get, path).host(serviceUrl.url.getHost)
    feeServices.execute(serviceUrl.url, request).map { response =>
      mapper.objectMapper.readTree(response.contentString)
        .fields.asScala.filter(_.getKey forall Character.isDigit)
        .map(_.getValue.asInt).toList.sorted.map(BigInt.apply) match {
        case low :: medium :: high :: Nil => BtcFeeInfo(high, medium, low)
        case _ =>
          warn(s"Failed to retrieve fees from explorer, falling back on default fees.")
          defaultBTCFeeInfo
      }
    }.asScala()
  }

  private def currencyServiceURLFor(currencyName: String): CurrencyServiceURL =
    currencyServiceUrl.getOrElse(currencyName, currencyServiceUrl("default"))

  private def feesPathForCurrency(currencyName: String): String =
    DaemonConfiguration.explorer.api.paths.get(currencyName).flatMap(_.feesPath)
      .fold(throw new UnsupportedOperationException(s"Currency not supported '$currencyName'"))(_.path)


  def getFeesRipple: Future[RippleFeeInfo] = {
    val serviceUrl: CurrencyServiceURL = currencyServiceURLFor("ripple")
    val request = Request(Method.Post, "/").host(serviceUrl.url.getHost)
    val body = "{\"method\":\"fee\",\"params\":[{}]}"
    request.setContentString(body)
    request.setContentType("application/json")

    feeServices.execute(serviceUrl.url, request).map { response =>
      import io.circe.parser.parse
      val json = parse(response.contentString)
      val result = json.flatMap { j =>
        for {
          rippleResult <- j.hcursor.get[Json]("result")
          drops <- rippleResult.hcursor.get[Json]("drops")
          openLedgerFee <- drops.hcursor.get[Int]("open_ledger_fee")
          baseFee <- drops.hcursor.get[Int]("base_fee")
          levels <- rippleResult.hcursor.get[Json]("levels")
          loadFactor <- levels.hcursor.get[Int]("open_ledger_level")
          loadBase <- levels.hcursor.get[Int]("reference_level")
        } yield {
          info(s"Query rippled fee method: baseFee=$baseFee loadFactor:$loadFactor loadBase=$loadBase, openLedgerFee=$openLedgerFee")
          RippleFeeInfo(baseFee, loadFactor, loadBase, openLedgerFee)
        }
      }

      result.getOrElse {
        info(s"Failed to query fee method of ripple daemon: " +
          s"uri=${serviceUrl.url.getHost} request=${request.contentString} response=${response.contentString}, using default XRP fees $defaultXRPFeesInfo")
        defaultXRPFeesInfo
      }
    }
  }.asScala()

  def getGasLimit(currency: core.Currency, recipient: String, source: Option[String] = None, inputData: Option[Array[Byte]] = None): Future[BigInt] = {
    import io.circe.syntax._
    val serviceUrl: CurrencyServiceURL = currencyServiceURLFor(currency.getName)
    val path = s"/blockchain/v3/${currency.getEthereumLikeNetworkParameters.getIdentifier}/addresses/${recipient.toLowerCase}/estimate-gas-limit"
    val request = Request(Method.Post, path).host(serviceUrl.url.getHost)
    val body = source.map(s => Map[String, String]("from" -> s)).getOrElse(Map[String, String]()) ++
      inputData.map(d => Map[String, String]("data" -> s"0x${HexUtils.valueOf(d)}")).getOrElse(Map[String, String]())
    request.setContentString(body.asJson.noSpaces)
    request.setContentType("application/json")

    feeServices.execute(serviceUrl.url, request).map { response =>
      Try(mapper.parse[GasLimit](response).limit).fold(
        _ => {
          info(s"Failed to estimate gas limit, using default: Request=${request.contentString} ; Response=${response.contentString}")
          defaultGasLimit
        },
        result => {
          info(s"getGasLimit url=$serviceUrl request=${request.contentString} response:${response.contentString}")
          result
        }
      )
    }.asScala()
  }

  def getGasPrice(currencyName: String): Future[EthFeeInfo] = {
    val serviceUrl: CurrencyServiceURL = currencyServiceURLFor(currencyName)
    val path = feesPathForCurrency(currencyName)
    val request = Request(Method.Get, path).host(serviceUrl.url.getHost)
    feeServices.execute(serviceUrl.url, request).map { response =>
      EthFeeInfo(mapper.parse[GasPrice](response))
    }.asScala()
  }

  private val defaultGasLimit = BigInt(200000)
  private val defaultXRPFeesInfo = RippleFeeInfo(10, 256, 256, 10)

  // {"2":18281,"3":12241,"6":10709,"last_updated":1580478904}
  private val defaultBTCFeeInfo: BtcFeeInfo =
    BtcFeeInfo(18281, 12241, 10709)
}

object ApiClient {

  val feeServices = new ScalaHttpClientPool()
  val fallbackServices = new ScalaHttpClientPool()

  trait FeeInfo {
    def getAmount(feeMethod: FeeMethod): BigInt

    def slow: BigInt = getAmount(FeeMethod.SLOW)

    def normal: BigInt = getAmount(FeeMethod.NORMAL)

    def fast: BigInt = getAmount(FeeMethod.FAST)
  }

  case class BtcFeeInfo(fastFee: BigInt, normalFee: BigInt, slowFee: BigInt) extends FeeInfo {
    def getAmount(feeMethod: FeeMethod): BigInt = feeMethod match {
      case FeeMethod.FAST => fastFee / 1000
      case FeeMethod.NORMAL => normalFee / 1000
      case FeeMethod.SLOW => slowFee / 1000
    }
  }

  case class RippleFeeInfo(baseFee: Int, loadFactor: Int, loadBase: Int, openLedgerCost: Int) extends FeeInfo {
    val loadCoast: Int = ((baseFee * loadFactor).toDouble / loadBase.toDouble).intValue()

    def getAmount(feeMethod: FeeMethod): BigInt = feeMethod match {
      case FeeMethod.SLOW => BigInt(baseFee)
      case FeeMethod.NORMAL => BigInt(baseFee + loadCoast)
      case FeeMethod.FAST => BigInt((baseFee + (loadCoast + openLedgerCost) * 1.5D).toInt)
    }
  }

  case class EthFeeInfo(gasPrice: GasPrice) extends FeeInfo {
    def getAmount(feeMethod: FeeMethod): BigInt = feeMethod match {
      case FeeMethod.SLOW => (BigDecimal(gasPrice.price) * ETH_SLOW_FEES_FACTOR).toBigInt
      case FeeMethod.NORMAL => (BigDecimal(gasPrice.price) * ETH_NORMAL_FEES_FACTOR).toBigInt
      case FeeMethod.FAST => (BigDecimal(gasPrice.price) * ETH_FAST_FEES_FACTOR).toBigInt
    }
  }

  case class GasPrice(@JsonProperty("gas_price") price: BigInt)

  case class GasLimit(@JsonProperty("estimated_gas_limit") limit: BigInt)

  case class XlmFeeInfo(minimumFee: BigInt) extends FeeInfo {
    def getAmount(feeMethod: FeeMethod): BigInt = feeMethod match {
      case FeeMethod.SLOW => minimumFee
      case FeeMethod.NORMAL => minimumFee * 2
      case FeeMethod.FAST => minimumFee * 10
    }
  }

  case class XtzFeeInfo(fee: BigInt) extends FeeInfo {
    def getAmount(feeMethod: FeeMethod): BigInt = feeMethod match {
      case FeeMethod.SLOW => fee - (fee * 25) / 100
      case FeeMethod.NORMAL => fee
      case FeeMethod.FAST => fee + (fee * 25) / 100
    }
  }
}
