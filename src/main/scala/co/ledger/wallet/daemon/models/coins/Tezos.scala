package co.ledger.wallet.daemon.models.coins

import java.util.Date

import co.ledger.core
import co.ledger.wallet.daemon.clients.ApiClient.XtzFeeInfo
import co.ledger.wallet.daemon.models.coins.Coin._
import co.ledger.wallet.daemon.models.{Currency, FeeMethod}
import co.ledger.wallet.daemon.utils.HexUtils
import co.ledger.wallet.daemon.utils.Utils.RichBigInt
import com.fasterxml.jackson.annotation.JsonProperty

import scala.collection.JavaConverters._
import scala.util.Try

object Tezos {
  val currencyFamily = core.WalletType.TEZOS

  def newNetworkParamsView(from: core.TezosLikeNetworkParameters): NetworkParamsView = {
    TezosNetworkParamsView(
      from.getIdentifier,
      from.getMessagePrefix,
      HexUtils.valueOf(from.getXPUBVersion),
      HexUtils.valueOf(from.getImplicitPrefix),
      HexUtils.valueOf(from.getOriginatedPrefix),
      from.getAdditionalTIPs.asScala.toList,
      from.getTimestampDelay
    )
  }

  def newTransactionView(from: core.TezosLikeTransaction): TransactionView = {
    TezosTransactionView(
      from.getType,
      from.getHash,
      Option(from.getFees).map(fees => fees.toString),
      Option(from.getReceiver).map(recv => recv.toString),
      from.getSender.toString,
      from.getValue.toString,
      from.getDate,
      HexUtils.valueOf(from.getSigningPubKey),
      Try(from.getCounter).toOption.map(counter => counter.asScala),
      Option(from.getGasLimit).map(limit => limit.toBigInt.asScala),
      Try(from.getStorageLimit).toOption.map(limit => limit.asScala),
      from.getBlockHash,
      from.getStatus
    )
  }
}

case class TezosNetworkParamsView(
                                   @JsonProperty("identifier") identifier: String,
                                   @JsonProperty("message_prefix") messagePrefix: String,
                                   @JsonProperty("xpub_version") xpubVersion: String,
                                   @JsonProperty("implicit_prefix") implicitPrefix: String,
                                   @JsonProperty("originated_prefix") originatedPrefix: String,
                                   @JsonProperty("additional_tips") additionalTips: List[String],
                                   @JsonProperty("timestamp_delay") timestampDelay: BigInt
                                 ) extends NetworkParamsView

object TezosNetworkParamsView {
  def apply(n: core.TezosLikeNetworkParameters): TezosNetworkParamsView =
    TezosNetworkParamsView(
      n.getIdentifier,
      n.getMessagePrefix,
      HexUtils.valueOf(n.getXPUBVersion),
      HexUtils.valueOf(n.getImplicitPrefix),
      HexUtils.valueOf(n.getOriginatedPrefix),
      n.getAdditionalTIPs.asScala.toList,
      n.getTimestampDelay
    )
}

case class TezosTransactionView(
                                 @JsonProperty("type") operationType: core.TezosOperationTag,
                                 @JsonProperty("hash") hash: String,
                                 @JsonProperty("fees") fees: Option[String],
                                 @JsonProperty("receiver") receiver: Option[String],
                                 @JsonProperty("sender") sender: String,
                                 @JsonProperty("value") value: String,
                                 @JsonProperty("date") date: Date,
                                 @JsonProperty("signing_pubkey") signing_pubkey: String,
                                 @JsonProperty("counter") counter: Option[BigInt],
                                 @JsonProperty("gas_limit") gasLimit: Option[BigInt],
                                 @JsonProperty("storage_limit") storageLimit: Option[BigInt],
                                 @JsonProperty("block_hash") blockHash: String,
                                 @JsonProperty("status") status: Int
                               ) extends TransactionView

object TezosTransactionView {
  def apply(tx: core.TezosLikeTransaction): TezosTransactionView = {
    TezosTransactionView(
      tx.getType,
      tx.getHash,
      Option(tx.getFees).map(fees => fees.toString),
      Option(tx.getReceiver).map(recv => recv.toBase58),
      tx.getSender.toBase58,
      tx.getValue.toString,
      tx.getDate,
      HexUtils.valueOf(tx.getSigningPubKey),
      Try(tx.getCounter).toOption.map(counter => counter.asScala),
      Option(tx.getGasLimit).map(limit => limit.toBigInt.asScala),
      Try(tx.getStorageLimit).toOption.map(limit => limit.asScala),
      tx.getBlockHash,
      tx.getStatus
    )
  }

  def apply(op: core.Operation): TezosTransactionView = {
    this.apply(op.asTezosLikeOperation().getTransaction)
  }
}

case class UnsignedTezosTransactionView(
                                         @JsonProperty("type") operationType: core.TezosOperationTag,
                                         @JsonProperty("hash") hash: String,
                                         @JsonProperty("fees") fees: Option[String],
                                         @JsonProperty("receiver") receiver: Option[String],
                                         @JsonProperty("sender") sender: String,
                                         @JsonProperty("value") value: Option[String],
                                         @JsonProperty("date") date: Date,
                                         @JsonProperty("signing_pubkey") signing_pubkey: String,
                                         @JsonProperty("counter") counter: Option[BigInt],
                                         @JsonProperty("gas_limit") gasLimit: Option[BigInt],
                                         @JsonProperty("storage_limit") storageLimit: Option[BigInt],
                                         @JsonProperty("block_hash") blockHash: String,
                                         @JsonProperty("status") status: Int,
                                         @JsonProperty("raw_transaction") rawTransaction: String) extends TransactionView

object UnsignedTezosTransactionView {
  def apply(tx: core.TezosLikeTransaction, feeMethod: FeeMethod): UnsignedTezosTransactionView = {
    UnsignedTezosTransactionView(
      tx.getType,
      tx.getHash,
      Option(tx.getFees).map(fees => Currency.convertAmount(fees.getCurrency)(XtzFeeInfo(fees.toBigInt.asScala).getAmount(feeMethod)))
        .map(fees => fees.toString),
      Option(tx.getReceiver).map(recv => recv.toBase58),
      tx.getSender.toBase58,
      Option(tx.getValue).map(_.toString),
      tx.getDate,
      HexUtils.valueOf(tx.getSigningPubKey),
      Try(tx.getCounter).toOption.map(counter => counter.asScala),
      Option(tx.getGasLimit).map(limit => limit.toBigInt.asScala),
      Try(tx.getStorageLimit).toOption.map(limit => limit.asScala),
      tx.getBlockHash,
      tx.getStatus,
      HexUtils.valueOf(tx.serialize())
    )
  }
}
