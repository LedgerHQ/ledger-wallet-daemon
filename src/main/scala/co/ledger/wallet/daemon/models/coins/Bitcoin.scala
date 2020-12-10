package co.ledger.wallet.daemon.models.coins

import java.util.Date

import co.ledger.core
import co.ledger.core.implicits._
import co.ledger.wallet.daemon.async.MDCPropagatingExecutionContext.Implicits.global
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.models.coins.Coin._
import co.ledger.wallet.daemon.utils.HexUtils
import com.fasterxml.jackson.annotation.JsonProperty

import scala.collection.JavaConverters._
import scala.concurrent.Future

object Bitcoin {
  val currencyFamily = core.WalletType.BITCOIN

  def newNetworkParamsView(currencyName: String, from: core.BitcoinLikeNetworkParameters): NetworkParamsView = {
    BitcoinNetworkParamsView(
      from.getIdentifier,
      HexUtils.valueOf(from.getP2PKHVersion),
      HexUtils.valueOf(from.getP2SHVersion),
      HexUtils.valueOf(from.getXPUBVersion),
      from.getFeePolicy.name,
      from.getDust,
      from.getMessagePrefix,
      from.getUsesTimestampedTransaction,
      hasNativeSegwitSupport = DaemonConfiguration.supportedNativeSegwitCurrencies.contains(currencyName)
    )
  }

  def newTransactionView(from: core.BitcoinLikeTransaction): TransactionView = {
    BitcoinTransactionView(
      Option(from.getBlock).map (newBlockView),
      Option(from.getFees).map (fees => fees.toString),
      from.getHash,
      from.getTime,
      from.getInputs.asScala.map(newInputView),
      from.getLockTime,
      from.getOutputs.asScala.map(newOutputView)
    )
  }

  def newUnsignedTransactionView(from: core.BitcoinLikeTransaction, feesPerByte: BigInt, partial: Boolean): Future[UnsignedBitcoinTransactionView] = {
    Future.sequence(from.getInputs.asScala.map(newUnsignedInputView(_, partial))).map { unsignedInputs =>
      UnsignedBitcoinTransactionView(
        newEstimatedSizeView(from.getEstimatedSize),
        from.getFees.toString,
        feesPerByte.toString,
        unsignedInputs,
        from.getLockTime,
        from.getOutputs.asScala.map(newUnsignedOutputView),
        HexUtils.valueOf(from.serialize())
      )
    }
  }

  private def newUnsignedOutputView(from: core.BitcoinLikeOutput): UnsignedBitcoinOutputView = {
    UnsignedBitcoinOutputView(
      from.getAddress,
      from.getValue.toString,
      HexUtils.valueOf(from.getScript),
      Option(from.getDerivationPath).map(_.toString)
    )
  }

  private def newEstimatedSizeView(from: core.EstimatedSize): EstimatedSizeView = {
    EstimatedSizeView(from.getMax, from.getMin)
  }

  private def newBlockView(from: core.BitcoinLikeBlock): BlockView = {
    CommonBlockView(from.getHash, from.getHeight, from.getTime)
  }

  private def newUnsignedInputView(from: core.BitcoinLikeInput, partial: Boolean): Future[UnsignedBitcoinInputView] = {
    // This getter is actually retrieving behind the scene the raw tx from explorer. It should not be called when transaction is partial
    val txHex = if (partial) Future.successful(Array.empty[Byte]) else from.getPreviousTransaction()
    val derivationPath = for {
      path <- from.getDerivationPath.asScala
      pubKey <- from.getPublicKeys.asScala
    } yield (path.toString, HexUtils.valueOf(pubKey))
    txHex.map( fullTx =>
    UnsignedBitcoinInputView(
      from.getAddress,
      from.getValue.toString,
      from.getPreviousTxHash,
      HexUtils.valueOf(fullTx),
      from.getPreviousOutputIndex,
      derivationPath.toMap)
    )
  }

  private def newInputView(from: core.BitcoinLikeInput): InputView = {
    val previousIndex: Int = from.getPreviousOutputIndex
    BitcoinInputView(from.getAddress, from.getValue.toString, Option(from.getCoinbase), Option(from.getPreviousTxHash), Option(previousIndex))
  }

  private def newOutputView(from: core.BitcoinLikeOutput): OutputView = {
    BitcoinOutputView(from.getAddress, from.getTransactionHash, from.getOutputIndex, from.getValue.toString, HexUtils.valueOf(from.getScript))
  }
}

case class BitcoinNetworkParamsView(
                                     @JsonProperty("identifier") identifier: String,
                                     @JsonProperty("p2pkh_version") p2pkhVersion: String,
                                     @JsonProperty("p2sh_version") p2shVersion: String,
                                     @JsonProperty("xpub_version") xpubVersion: String,
                                     @JsonProperty("fee_policy") feePolicy: String,
                                     @JsonProperty("dust_amount") dustAmount: BigInt,
                                     @JsonProperty("message_prefix") messagePrefix: String,
                                     @JsonProperty("uses_timestamped_transaction") usesTimeStampedTransaction: Boolean,
                                     @JsonProperty("has_native_segwit_support") hasNativeSegwitSupport: Boolean
                                   ) extends NetworkParamsView

case class BitcoinTransactionView(
                                   @JsonProperty("block") block: Option[BlockView],
                                   @JsonProperty("fees") fees: Option[String],
                                   @JsonProperty("hash") hash: String,
                                   @JsonProperty("time") time: Date,
                                   @JsonProperty("inputs") inputs: Seq[InputView],
                                   @JsonProperty("lock_time") lockTime: Long,
                                   @JsonProperty("outputs") outputs: Seq[OutputView]
                                 ) extends TransactionView

case class BitcoinInputView(
                           @JsonProperty("address") address: String,
                           @JsonProperty("value") value: String,
                           @JsonProperty("coinbase") coinbase: Option[String],
                           @JsonProperty("previous_transaction_hash") previousTxHash: Option[String],
                           @JsonProperty("previous_output_index") previousOutputIndex: Option[Int]
                           ) extends InputView

case class BitcoinOutputView(
                            @JsonProperty("address") address: String,
                            @JsonProperty("transaction_hash") txHash: String,
                            @JsonProperty("output_index") outputIndex: Int,
                            @JsonProperty("value") value: String,
                            @JsonProperty("script") script: String
                            ) extends OutputView

case class UnsignedBitcoinInputView(
                                   @JsonProperty("address") address: String,
                                   @JsonProperty("value") value: String,
                                   @JsonProperty("previous_transaction_hash") previousTxHash: String,
                                   @JsonProperty("previous_transaction_raw") previousTx: String,
                                   @JsonProperty("previous_output_index") previousOutputIndex: Int,
                                   @JsonProperty("derivation_paths") derivationPaths: Map[String, String]
                                   ) extends InputView

case class UnsignedBitcoinOutputView(
                                    @JsonProperty("address") address: String,
                                    @JsonProperty("value") value: String,
                                    @JsonProperty("script") script: String,
                                    @JsonProperty("derivation_path") derivationPath: Option[String]
                                    ) extends OutputView

case class UnsignedBitcoinTransactionView(
                            @JsonProperty("estimated_size") estimatedSize: EstimatedSizeView,
                            @JsonProperty("fees") fees: String,
                            @JsonProperty("fees_per_byte") feesPerByte: String,
                            @JsonProperty("inputs") inputs: Seq[UnsignedBitcoinInputView],
                            @JsonProperty("lock_time") lockTime: Long,
                            @JsonProperty("outputs") outputs: Seq[UnsignedBitcoinOutputView],
                            @JsonProperty("raw_transaction") rawTransaction: String) extends TransactionView

case class EstimatedSizeView(@JsonProperty("max") max: Int, @JsonProperty("min") min: Int)
