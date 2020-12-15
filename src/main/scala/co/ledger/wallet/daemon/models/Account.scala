package co.ledger.wallet.daemon.models

import cats.implicits._
import co.ledger.core
import co.ledger.core._
import co.ledger.core.implicits.{InvalidEIP55FormatException, NotEnoughFundsException, UnsupportedOperationException, _}
import co.ledger.wallet.daemon.clients.ApiClient.{XlmFeeInfo, XtzFeeInfo}
import co.ledger.wallet.daemon.clients.ClientFactory
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.controllers.TransactionsController._
import co.ledger.wallet.daemon.exceptions._
import co.ledger.wallet.daemon.libledger_core.async.LedgerCoreExecutionContext
import co.ledger.wallet.daemon.models.Currency._
import co.ledger.wallet.daemon.models.Operations.OperationView
import co.ledger.wallet.daemon.models.coins.Coin.TransactionView
import co.ledger.wallet.daemon.models.coins._
import co.ledger.wallet.daemon.schedulers.observers.{SynchronizationEventReceiver, SynchronizationResult}
import co.ledger.wallet.daemon.services.SyncStatus
import co.ledger.wallet.daemon.utils.HexUtils
import co.ledger.wallet.daemon.utils.Utils.{RichBigInt, RichCoreBigInt, _}
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.common.primitives.UnsignedInteger
import com.twitter.inject.Logging

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.math.BigDecimal
import scala.math.BigDecimal.int2bigDecimal

object Account extends Logging {
  implicit class RichCoreAccount(val a: core.Account) extends AnyVal {
    def erc20Balance(contract: String)(implicit ec: ExecutionContext): Future[scala.BigInt] = {
      Account.erc20Balance(contract, a)
    }

    def erc20Balances(contracts: Option[Array[String]])(implicit ex: ExecutionContext): Future[Seq[scala.BigInt]] = {
      val contractList = contracts.getOrElse(a.asEthereumLikeAccount().getERC20Accounts.asScala.toArray.map(ercAccount => ercAccount.getToken.getContractAddress))
      a.asEthereumLikeAccount().getERC20Balances(contractList).map(_.asScala.map(coreBi => coreBi.asScala))
    }

    def erc20Operations(w: Wallet, contract: String)(implicit ec: ExecutionContext): Future[List[OperationView]] =
      Account.erc20Operations(contract, w, a)

    def erc20Operations(w: Wallet)(implicit ec: ExecutionContext): Future[List[OperationView]] =
      Account.erc20Operations(w, a)

    def batchedErc20Operations(w: Wallet, contract: String, limit: Int, batch: Int)(implicit ec: ExecutionContext): Future[List[OperationView]] =
      Account.batchedErc20Operations(contract, w, a, limit, batch)

    def batchedErc20Operations(w: Wallet, limit: Int, batch: Int)(implicit ec: ExecutionContext): Future[List[OperationView]] =
      Account.batchedErc20Operations(w, a, limit, batch)

    def erc20Accounts: Either[Exception, List[core.ERC20LikeAccount]] =
      Account.erc20Accounts(a)

    def erc20Account(tokenAddress: String): Either[Exception, core.ERC20LikeAccount] =
      asERC20Account(tokenAddress, a)

    def getUtxo(referenceHeight: Long, offset: Int, batch: Int)(implicit ec: ExecutionContext): Future[List[UTXOView]] = {
      Account.getUtxo(referenceHeight, offset, batch, a)
    }

    def getUtxoCount: Future[Int] = {
      Account.getUtxoCount(a)
    }

    def balance(implicit ec: ExecutionContext): Future[scala.BigInt] =
      Account.balance(a)

    def balances(start: String, end: String, timePeriod: core.TimePeriod)(implicit ec: ExecutionContext): Future[List[scala.BigInt]] =
      Account.balances(start, end, timePeriod, a)

    def firstOperationView(w: Wallet)(implicit ec: ExecutionContext): Future[Option[OperationView]] =
      Account.firstOperationView(w, a)

    def operationCounts(implicit ec: ExecutionContext): Future[Map[core.OperationType, Int]] =
      Account.operationCounts(a)

    def accountView(pool: Pool, wallet: Wallet, cv: CurrencyView, syncStatus: SyncStatus)(implicit ec: ExecutionContext): Future[AccountView] =
      Account.accountView(pool, wallet, cv, a, syncStatus)

    def erc20AccountView(erc20Acc: ERC20LikeAccount, wallet: Wallet, syncStatus: SyncStatus)(implicit ec: ExecutionContext): Future[ERC20FullAccountView] =
      Account.erc20AccountView(a, erc20Acc, wallet, syncStatus)

    def broadcastBTCTransaction(rawTx: Array[Byte], signatures: Seq[BTCSigPub], currentHeight: Long, c: core.Currency): Future[String] =
      Account.broadcastBTCTransaction(rawTx, signatures, currentHeight, a, c)

    def broadcastETHTransaction(rawTx: Array[Byte], signatures: ETHSignature, c: core.Currency)(implicit ec: ExecutionContext): Future[String] =
      Account.broadcastETHTransaction(rawTx, signatures, a, c)

    def broadcastXRPTransaction(rawTx: Array[Byte], signatures: XRPSignature, c: core.Currency)(implicit ec: ExecutionContext): Future[String] =
      Account.broadcastXRPTransaction(rawTx, signatures, a, c)

    def broadcastXLMTransaction(rawTx: Array[Byte], signatures: XLMSignature, c: core.Currency): Future[String] =
      Account.broadcastXLMTransaction(rawTx, signatures, a, c)

    def getXRPTransactionHash(rawTx: Array[Byte], signatures: XRPSignature, c: core.Currency)(implicit ec: ExecutionContext): Future[String] =
      Account.getXRPTransactionHash(rawTx, signatures, c)

    def broadcastXTZTransaction(rawTx: Array[Byte], signatures: XTZSignature, c: core.Currency): Future[String] =
      Account.broadcastXTZTransaction(rawTx, signatures, a, c)

    def createTransaction(transactionInfo: TransactionInfo, w: core.Wallet)(implicit ec: ExecutionContext): Future[TransactionView] =
      Account.createTransaction(transactionInfo, a, w)


    def operationView(uid: String, fullOp: Int, w: Wallet)(implicit ec: ExecutionContext): Future[Option[OperationView]] = Account.operationView(uid, fullOp, w, a)

    def operationViews(offset: Int, batch: Int, fullOp: Int, w: Wallet)(implicit ec: ExecutionContext): Future[Seq[OperationView]] =
      Account.operationViews(offset, batch, fullOp, a.queryOperations(), w, a)

    def latestOperationViews(latests: Int, w: Wallet)(implicit ec: ExecutionContext): Future[Seq[OperationView]] =
      Account.latestOperationViews(latests, a.queryOperations(), w, a)

    def operationViewsFromHeight(offset: Int, batch: Int, fullOp: Int, fromHeight: Long, w: Wallet)(implicit ec: ExecutionContext): Future[Seq[OperationView]] = {
      val opQuery: OperationQuery = a.queryOperations()
      opQuery.filter().opAnd(QueryFilter.blockHeightGt(fromHeight).opOr(QueryFilter.blockHeightIsNull()))
      Account.operationViews(offset, batch, fullOp, opQuery, w, a)
    }

    def erc20OperationsFromHeight(pool: Pool, wallet: Wallet, account: Account, offset: Int, batch: Int, fromHeight: Long): Future[Seq[OperationView]] =
      pool.walletPoolDao.findERC20OperationsFromBlockHeight(account, wallet, fromHeight, offset, batch).asScala()

    def freshAddresses(implicit ec: ExecutionContext): Future[Seq[core.Address]] =
      Account.freshAddresses(a)

    def getAddressesInRange(from: Long, to: Long)(implicit ec: ExecutionContext): Future[Seq[core.Address]] =
      Account.getAddressesInRange(from, to, a)

    def sync(poolName: String, walletName: String)(implicit ec: ExecutionContext): Future[SynchronizationResult] =
      Account.sync(poolName, walletName, a)

    def getDelegations()(implicit ec: ExecutionContext): Future[Seq[DelegationView]] =
      Account.getDelegation(a)
  }

  def balance(a: core.Account)(implicit ex: ExecutionContext): Future[scala.BigInt] = a.getBalance().map { b =>
    debug(s"Account ${a.getWalletType}:${a.getIndex}, balance: $b")
    b.toBigInt.asScala
  }

  private def asETHAccount(a: core.Account): Either[Exception, EthereumLikeAccount] = {
    a.getWalletType match {
      case WalletType.ETHEREUM => Right(a.asEthereumLikeAccount())
      case _ => Left(new UnsupportedOperationException("current account is not an ETH account"))
    }
  }

  private def asERC20Account(contract: String, a: core.Account): Either[Exception, ERC20LikeAccount] = {
    asETHAccount(a).flatMap(_.getERC20Accounts.asScala.find(_.getToken.getContractAddress == contract) match {
      case Some(t) => Right(t)
      case None =>
        warn(s"Requested an erc20 account but it has not been found : $contract")
        Left(ERC20NotFoundException(contract))
    })
  }

  def getUtxo(referenceHeight: Long, offset: Int, batch: Int, a: core.Account)(implicit ex: ExecutionContext): Future[List[UTXOView]] = {
    a.asBitcoinLikeAccount().getUTXO(offset, offset + batch).map(_.asScala.toList.map(UTXOView.fromBitcoinOutput(_, referenceHeight)))
  }

  def getUtxoCount(a: core.Account): Future[Int] = {
    a.asBitcoinLikeAccount().getUTXOCount()
  }

  def erc20Accounts(a: core.Account): Either[Exception, List[core.ERC20LikeAccount]] =
    asETHAccount(a).map(_.getERC20Accounts.asScala.toList)


  def erc20Balance(contract: String, a: core.Account)(implicit ec: ExecutionContext): Future[scala.BigInt] =
    for {
      account <- asERC20Account(contract, a).liftTo[Future]
      balance <- account.getBalance()
    } yield balance.asScala

  def erc20Operations(contract: String, w: Wallet, a: core.Account)(implicit ec: ExecutionContext): Future[List[OperationView]] =
    asERC20Account(contract, a).liftTo[Future].flatMap(erc20Operations(w, a, _))

  def erc20Operations(w: Wallet, a: core.Account)(implicit ec: ExecutionContext): Future[List[OperationView]] =
    for {
      account <- asETHAccount(a).liftTo[Future]
      ops <- account.getERC20Accounts.asScala.toList.flatTraverse(erc20Operations(w, a, _))
    } yield ops

  private def erc20Operations(w: Wallet, a: core.Account, aErc20: core.ERC20LikeAccount)(implicit ec: ExecutionContext): Future[List[OperationView]] =
    aErc20.queryOperations().complete().executeAndDestroy().map(_.asScala.toList).map { coreOps =>
      val hashToCoreOps = coreOps.map(coreOp => coreOp.asEthereumLikeOperation().getTransaction.getHash -> coreOp).toMap
      val erc20Ops = aErc20.getOperations.asScala.toList
      erc20Ops.filter(erc20Op => {
        val opFound = hashToCoreOps.contains(erc20Op.getHash)
        if (!opFound) {
          error(s"Requested operation ${erc20Op.getHash} from account ${aErc20.getAddress} has been skipped due to inconsistencies.")
        }
        opFound
      }
      ).map(erc20Op => Operations.getErc20View(erc20Op, hashToCoreOps(erc20Op.getHash), w, a)).sequence
    }.flatten

  def batchedErc20Operations(contract: String, w: Wallet, a: core.Account, offset: Int, batch: Int)(implicit ec: ExecutionContext): Future[List[OperationView]] =
    asERC20Account(contract, a).liftTo[Future].flatMap(erc20Acc => batchedErc20Operations(w, a, erc20Acc, offset, batch))

  def batchedErc20Operations(w: Wallet, a: core.Account, offset: Int, batch: Int)
                            (implicit ec: ExecutionContext): Future[List[OperationView]] =
    for {
      account <- asETHAccount(a).liftTo[Future]
      ops = account.getERC20Accounts.asScala.toList.map(erc20Account => batchedErc20Operations(w, a, erc20Account, offset, batch))
      result <- Future.sequence(ops).map(_.flatten)
    } yield result

  private def batchedErc20Operations(w: Wallet, acc: Account, a: core.ERC20LikeAccount, offset: Int, batch: Int)
                                    (implicit ec: ExecutionContext): Future[List[OperationView]] =
    a.queryOperations().offset(offset).limit(batch).addOrder(OperationOrderKey.DATE, true).complete().executeAndDestroy()
      .map(_.asScala.toList)
      .map { coreOps =>
        val hashToERC20 = a.getOperations.asScala.toList.map(erc20Op => erc20Op.getHash -> erc20Op).toMap
        coreOps.map(coreOp => Operations.getErc20View(hashToERC20(coreOp.asEthereumLikeOperation().getTransaction.getHash), coreOp, w, acc)).sequence
      }.flatten

  def operationCounts(a: core.Account)(implicit ex: ExecutionContext): Future[Map[core.OperationType, Int]] =
    a.queryOperations().addOrder(OperationOrderKey.DATE, true).partial().executeAndDestroy().map { os =>
      os.asScala.groupBy(o => o.getOperationType).map { case (optType, opts) => (optType, opts.size) }
    }

  def accountView(pool: Pool, w: Wallet, cv: CurrencyView, a: core.Account, syncStatus: SyncStatus)(implicit ex: ExecutionContext): Future[AccountView] =
    for {
      b <- balance(a)
      opsCount <- pool.walletPoolDao.countOperations(a, w).asScala()
    } yield AccountView(w.getName, a.getIndex, b, opsCount, a.getRestoreKey, cv, syncStatus)

  def erc20AccountView(
                        a: core.Account,
                        erc20Account: ERC20LikeAccount,
                        wallet: Wallet,
                        syncStatus: SyncStatus
                      )(implicit ex: ExecutionContext): Future[ERC20FullAccountView] = {
    for {
      balance <- a.erc20Balance(erc20Account.getToken.getContractAddress)
    } yield ERC20FullAccountView.fromERC20Account(
      erc20Account,
      a,
      syncStatus,
      balance,
      wallet.getName
    )
  }

  def broadcastBTCTransaction(rawTx: Array[Byte], signatures: Seq[BTCSigPub], currentHeight: Long, a: core.Account, c: core.Currency): Future[String] = {
    c.parseUnsignedBTCTransaction(rawTx, currentHeight) match {
      case Right(tx) =>
        if (tx.getInputs.size != signatures.size) Future.failed(SignatureSizeUnmatchException(tx.getInputs.size(), signatures.size))
        else {
          tx.getInputs.asScala.zipWithIndex.foreach { case (input, index) =>
            input.pushToScriptSig(c.concatSig(signatures(index)._1)) // signature
            input.pushToScriptSig(signatures(index)._2) // pubkey
          }
          debug(s"transaction after sign '${HexUtils.valueOf(tx.serialize())}'")
          a.asBitcoinLikeAccount().broadcastTransaction(tx)
        }
      case Left(m) => Future.failed(new UnsupportedOperationException(s"Account type not supported, can't broadcast BTC transaction: $m"))
    }
  }

  def broadcastETHTransaction(rawTx: Array[Byte], signature: ETHSignature, a: core.Account, c: core.Currency)(implicit ec: ExecutionContext): Future[String] = {
    c.parseUnsignedETHTransaction(rawTx) match {
      case Right(tx) =>
        // calculate the v from chain id
        val v: Long = c.getEthereumLikeNetworkParameters.getChainID.toLong * 2 + 35
        tx.setDERSignature(signature)
        tx.setVSignature(HexUtils.valueOf(v.toHexString))
        debug(s"transaction after sign '${HexUtils.valueOf(tx.serialize())}'")
        a.asEthereumLikeAccount().broadcastTransaction(tx).recoverWith {
          case _ =>
            tx.setVSignature(HexUtils.valueOf((v + 1).toHexString))
            debug(s"transaction after sign with V '${HexUtils.valueOf(tx.serialize())}'")
            a.asEthereumLikeAccount().broadcastTransaction(tx)
        }
      case Left(m) => Future.failed(new UnsupportedOperationException(s"Account type not supported, can't broadcast ETH transaction: $m"))
    }
  }

  def getSignedXRPTransaction(rawTx: Array[Byte], signature: XRPSignature, c: core.Currency): Future[RippleLikeTransaction] = {
    c.parseUnsignedXRPTransaction(rawTx) match {
      case Right(tx) =>
        tx.setDERSignature(signature)
        debug(s"transaction after sign '${HexUtils.valueOf(tx.serialize())}'")
        Future.successful(tx)

      case Left(m) => Future.failed(new UnsupportedOperationException(s"Account type not supported, can't broadcast XRP transaction: $m"))
    }
  }

  def getXRPTransactionHash(rawTx: Array[Byte], signature: XRPSignature, c: core.Currency)(implicit ec: ExecutionContext): Future[String] = {
    for {
      tx <- getSignedXRPTransaction(rawTx, signature, c)
      hash <- c.parseSignedXRPTransaction(tx.serialize()) match {
        case Right(v) => Future.successful(v.getHash)
        case Left(m) => Future.failed(new UnsupportedOperationException(s"Cannot parse signed XRP transaction: $m"))
      }
    } yield hash
  }

  def broadcastXRPTransaction(rawTx: Array[Byte], signature: XRPSignature, a: core.Account, c: core.Currency)(implicit ec: ExecutionContext): Future[String] = {
    for {
      tx <- getSignedXRPTransaction(rawTx, signature, c)
      txHash <- a.asRippleLikeAccount.broadcastTransaction(tx)
    } yield txHash
  }

  def broadcastXLMTransaction(rawTx: Array[Byte], signature: XLMSignature, a: core.Account, c: core.Currency): Future[String] = {
    c.parseUnsignedXLMTransaction(rawTx) match {
      case Right(tx) =>
        // set signature
        tx.putSignature(signature, tx.getSourceAccount)
        val signedRawTx = tx.toRawTransaction
        debug(s"transaction after sign '${HexUtils.valueOf(signedRawTx)}'")
        a.asStellarLikeAccount().broadcastRawTransaction(signedRawTx)

      case Left(m) => Future.failed(new UnsupportedOperationException(s"Account type not supported, can't broadcast XLM transaction: $m"))
    }
  }

  def broadcastXTZTransaction(rawTx: Array[Byte], signature: XTZSignature, a: core.Account, c: core.Currency): Future[String] = {
    c.parseUnsignedXTZTransaction(rawTx) match {
      case Right(tx) =>
        // set signature
        tx.setSignature(signature)
        val signedRawTx = tx.serialize
        debug(s"transaction after sign '${HexUtils.valueOf(signedRawTx)}'")
        a.asTezosLikeAccount().broadcastTransaction(tx)

      case Left(m) => Future.failed(new UnsupportedOperationException(s"Account type not supported, can't broadcast XTZ transaction: $m"))
    }
  }

  private def createBTCTransaction(ti: BTCTransactionInfo, a: core.Account, c: core.Currency)(implicit ec: ExecutionContext): Future[TransactionView] = {
    val partial: Boolean = ti.partialTx.getOrElse(false)
    for {
      // If fees are provided use it as it, else take the feeMethod to calculate it
      feesPerByte <- ti.fees match {
        case Some(amount) => Future.successful(amount)
        case None => ClientFactory.apiClient.getFees(c.getName).map(f => f.getAmount(ti.feesSpeedLevel.getOrElse(FeeMethod.NORMAL)))
      }
      builder = a.asBitcoinLikeAccount().buildTransaction(partial)
        .sendToAddress(c.convertAmount(ti.amount), ti.recipient)
        .setFeesPerByte(c.convertAmount(feesPerByte))
        .pickInputs(ti.pickingStrategy, UnsignedInteger.MAX_VALUE.intValue(), null)
      _ = for ((address, index) <- ti.excludeUtxos) builder.excludeUtxo(address, index)
      tx <- builder.build()
      v <- Bitcoin.newUnsignedTransactionView(tx, feesPerByte, partial)
    } yield v
  }

  private def createETHTransaction(ti: ETHTransactionInfo, a: core.Account, c: core.Currency)(implicit ec: ExecutionContext): Future[TransactionView] = {
    for {
      transactionBuilder <- ti.contract match {
        case Some(contract) => erc20TransactionBuilder(ti, a, c, contract)
        case None => ethereumTransactionBuilder(ti, a, c)
      }
      gasPrice <- ti.gasPrice match {
        case Some(gPrice) => Future.successful(gPrice)
        case None => ClientFactory.apiClient.getGasPrice(c.getName).map(ethInfo => ethInfo.getAmount(ti.feesSpeedLevel.getOrElse(FeeMethod.NORMAL)))
      }
      v <- transactionBuilder.setGasPrice(c.convertAmount(gasPrice)).build()
    } yield UnsignedEthereumTransactionView(v)
  }

  private def ethereumTransactionBuilder(ti: ETHTransactionInfo, a: core.Account, c: core.Currency)(implicit ec: ExecutionContext): Future[EthereumLikeTransactionBuilder] = {
    for {
      gasLimit <- ti.gasLimit match {
        case Some(amount) => Future.successful(amount)
        case None => ClientFactory.apiClient.getGasLimit(c, ti.contract.getOrElse(ti.recipient))
      }
    } yield {
      a.asEthereumLikeAccount()
        .buildTransaction()
        .setGasLimit(c.convertAmount(gasLimit))
        .sendToAddress(c.convertAmount(ti.amount), ti.recipient)
    }
  }

  private def erc20TransactionBuilder(ti: ETHTransactionInfo, a: core.Account, c: core.Currency, contract: String)(implicit ec: ExecutionContext): Future[EthereumLikeTransactionBuilder] = {
    a.asEthereumLikeAccount().getERC20Accounts.asScala.find(_.getToken.getContractAddress == contract) match {
      case Some(erc20Account) =>
        for {
          inputData <- erc20Account.getTransferToAddressData(BigInt.fromIntegerString(ti.amount.toString(10), 10), ti.recipient).recoverWith {
            case _: InvalidEIP55FormatException => Future.failed(InvalidEIP55Format(ti.recipient))
            case _: NotEnoughFundsException => Future.failed(ERC20BalanceNotEnough(contract, 0, ti.amount))
          }

          gasLimit <- ti.gasLimit match {
            case Some(amount) => Future.successful(amount)
            case None => ClientFactory.apiClient.getGasLimit(
              c, ti.contract.getOrElse(ti.recipient), Some(erc20Account.getAddress), Some(inputData)
            ).map {
              v =>
                (BigDecimal(v) * BigDecimal(DaemonConfiguration.ETH_SMART_CONTRACT_GAS_LIMIT_FACTOR))
                  .setScale(0, BigDecimal.RoundingMode.CEILING).toBigInt()
            }
          }
        } yield {
          a.asEthereumLikeAccount()
            .buildTransaction()
            .setGasLimit(c.convertAmount(gasLimit))
            .sendToAddress(c.convertAmount(0), contract)
            .setInputData(inputData)
        }
      case None => Future.failed(ERC20BalanceNotEnough(contract, 0, ti.amount))
    }
  }

  private def createXRPTransaction(ti: XRPTransactionInfo, a: core.Account, c: core.Currency)(implicit ec: ExecutionContext): Future[TransactionView] = {
    val builder = a.asRippleLikeAccount().buildTransaction()
    ti.sendTo.foreach(sendTo => builder.sendToAddress(c.convertAmount(sendTo.amount), sendTo.address))
    ti.wipeTo.foreach(builder.wipeToAddress)
    ti.memos.foreach(builder.addMemo)
    ti.destinationTag.foreach(builder.setDestinationTag)

    for {
      feeMethod <- Future.successful(ti.feesSpeedLevel.getOrElse(FeeMethod.NORMAL))
      fees <- ti.fees.fold(ClientFactory.apiClient.getFeesRipple.map(_.getAmount(feeMethod)))(Future.successful)
      _ = builder.setFees(c.convertAmount(fees))
      view <- builder.build().map(UnsignedRippleTransactionView.apply)
    } yield {
      info(s"Ripple Transaction created = $view Using fees method ($feeMethod) using network fees : ${ti.fees.isEmpty}")
      view
    }
  }

  private def createXLMTransaction(ti: XLMTransactionInfo, a: core.Account, w: core.Wallet)(implicit ec: ExecutionContext): Future[TransactionView] = {
    val builder = a.asStellarLikeAccount().buildTransaction()
    val stellarAccount = a.asStellarLikeAccount()
    val stellarWallet = w.asStellarLikeWallet()
    val currency = w.getCurrency

    for {
      // Set sequence
      // A transaction’s sequence number needs to match the account’s sequence number.
      // So, we need to get the account’s current sequence number from the network and then
      // sequence number is required to be increased with every transaction.
      currentSequence <- stellarAccount.getSequence()
      _ = builder.setSequence(currentSequence.add(core.BigInt.fromLong(1L)))

      // Set base fee
      baseFee <- ti.fees match {
        case Some(fee) => Future.successful(fee)
        case None => stellarAccount.getFeeStats().map { s =>
          val minimumFee = scala.BigInt(s.getModeAcceptedFee)
          val feeMethod = ti.feesSpeedLevel.getOrElse(FeeMethod.SLOW)
          XlmFeeInfo(minimumFee).getAmount(feeMethod)
        }
      }
      _ = builder.setBaseFee(currency.convertAmount(baseFee))

      // Set memo
      _ = ti.memo.foreach {
        case m: StellarTextMemo => builder.setTextMemo(m.value)
        case m: StellarNumberMemo => builder.setNumberMemo(m.bigIntValue.asCoreBigInt)
        case m: StellarHashMemo => builder.setHashMemo(m.byteArrayValue)
        case m: StellarReturnMemo => builder.setReturnMemo(m.byteArrayValue)
      }

      amount = currency.convertAmount(ti.amount)
      recipient = ti.recipient

      // Check if recipient exists
      recipientExists <- stellarWallet.exists(recipient)

      // Get base reserve
      baseReserve <- stellarAccount.getBaseReserve()

      // To activate a new account, send at least the base reserve amount.
      _ <- if (!recipientExists && amount.toLong < baseReserve.toLong) {
        Future.failed(AmountNotEnoughToActivateAccount(currency, baseReserve))
      } else {
        Future.unit
      }

      // Set amount
      _ = if (recipientExists) {
        builder.addNativePayment(recipient, amount)
      } else {
        builder.addCreateAccount(recipient, amount)
      }

      // build and parse as unsigned tx view
      view <- builder.build().map(tx => UnsignedStellarTransactionView(tx, ti))
    } yield view
  }

  private def createXTZTransaction(ti: XTZTransactionInfo, a: core.Account, w: core.Wallet)(implicit ec: ExecutionContext): Future[TransactionView] = {
    val tezosAccount = a.asTezosLikeAccount()
    val builder = tezosAccount.buildTransaction()
    val currency = w.getCurrency
    val feeMethod = ti.feesSpeedLevel.getOrElse(FeeMethod.SLOW)

    // Various transaction types : transaction / wipe to address / delegate / undelegate
    val isUndelegate = ti.recipient.isEmpty && ti.operationType == TezosOperationTag.OPERATION_TAG_DELEGATION
    if (!isUndelegate) {
      if (ti.wipeToAddress) {
        builder.wipeToAddress(ti.recipient)
      } else {
        val amount = currency.convertAmount(ti.amount)
        builder.sendToAddress(amount, ti.recipient)
      }
    }

    for {
      defaultFees <- ti.operationType match {
        case TezosOperationTag.OPERATION_TAG_TRANSACTION => Future.successful(0.toBigInt)
        case _ => tezosAccount.getFees() map { res =>
          // make sure the fees are within reasonable bounds ( in [2500 ; 30000] )
          val boundedFees = res.asScala.max(scala.math.BigInt(2500)).min(scala.math.BigInt(30000))
          XtzFeeInfo(boundedFees).getAmount(feeMethod)
        }
      }
      defaultGasLimit <- ti.operationType match {
        case TezosOperationTag.OPERATION_TAG_TRANSACTION => Future.successful(0.toBigInt)
        case _ => tezosAccount.getEstimatedGasLimit(ti.recipient).map(_.asScala)
      }
      storageLimit <- ti.storageLimit match {
        case Some(amount) => Future.successful(amount)
        case None => Future.successful(scala.math.BigInt(257))
      }

      _ = builder.setFees(currency.convertAmount(ti.fees.getOrElse(defaultFees)))
      _ = builder.setGasLimit(currency.convertAmount(ti.gasLimit.getOrElse(defaultGasLimit)))
      _ = builder.setStorageLimit(storageLimit.asCoreBigInt)

      _ = builder.setType(ti.operationType)
      // build and parse as unsigned tx view
      // libcore is responsible for creating/injecting the reveal operation if necessary
      // libcore also sets fees/gasLimit if values were not set
      tx <- builder.build()
    } yield UnsignedTezosTransactionView(tx, feeMethod)
  }

  def createTransaction(transactionInfo: TransactionInfo, a: core.Account, w: core.Wallet)(implicit ec: ExecutionContext): Future[TransactionView] = {
    info(s"Creating transaction $transactionInfo")
    val c = w.getCurrency
    (transactionInfo, c.getWalletType) match {
      case (ti: BTCTransactionInfo, WalletType.BITCOIN) => createBTCTransaction(ti, a, c)
      case (ti: ETHTransactionInfo, WalletType.ETHEREUM) => createETHTransaction(ti, a, c)
      case (ti: XRPTransactionInfo, WalletType.RIPPLE) => createXRPTransaction(ti, a, c)
      case (ti: XLMTransactionInfo, WalletType.STELLAR) => createXLMTransaction(ti, a, w)
      case (ti: XTZTransactionInfo, WalletType.TEZOS) => createXTZTransaction(ti, a, w)
      case _ => Future.failed(new UnsupportedOperationException("Account type not supported, can't create transaction"))
    }
  }

  def operationView(uid: String, fullOp: Int, w: Wallet, a: core.Account)(implicit ec: ExecutionContext): Future[Option[OperationView]] = {
    val q = a.queryOperations()
    q.filter().opAnd(core.QueryFilter.operationUidEq(uid))
    (if (fullOp > 0) q.complete().executeAndDestroy()
    else q.partial().executeAndDestroy()).flatMap { ops =>
      debug(s"Found ${ops.size()} operation(s) with uid : $uid")
      ops.asScala.headOption.map(Operations.getViewAndDestroy(_, w, a)).sequence
    }
  }

  def firstOperationView(w: Wallet, a: core.Account)(implicit ec: ExecutionContext): Future[Option[OperationView]] = {
    a.queryOperations().addOrder(OperationOrderKey.DATE, false).limit(1).partial().executeAndDestroy()
      .map { ops =>
        ops.asScala.toList.headOption
          .map(op => Operations.getViewAndDestroy(op, w, a)).sequence
      }.flatten
  }

  def operationViews(offset: Int, batch: Int, fullOp: Int, query: OperationQuery, wallet: Wallet, a: Account)
                    (implicit ec: ExecutionContext): Future[Seq[OperationView]] = {
    (if (fullOp > 0) {
      query.addOrder(OperationOrderKey.DATE, true).offset(offset).limit(batch).complete().executeAndDestroy()
    } else {
      query.addOrder(OperationOrderKey.DATE, true).offset(offset).limit(batch).partial().executeAndDestroy()
    }).map { operations =>
      Future.sequence(operations.asScala.toList.map(op => Operations.getViewAndDestroy(op, wallet, a)))
    }.flatten
  }

  def latestOperationViews(latests: Int, query: OperationQuery, w: Wallet, a: Account)
                          (implicit ec: ExecutionContext): Future[Seq[OperationView]] = {
    query.addOrder(OperationOrderKey.DATE, true).offset(0).limit(latests).complete().executeAndDestroy()
      .map { operations =>
        Future.sequence(operations.asScala.toList.map(Operations.getViewAndDestroy(_, w, a)))
      }.flatten
  }

  def balances(start: String, end: String, timePeriod: core.TimePeriod, a: core.Account)
              (implicit ec: ExecutionContext): Future[List[scala.BigInt]] = {
    a.getBalanceHistory(start, end, timePeriod).map { balances =>
      balances.asScala.toList.map { ba => ba.toBigInt.asScala }
    }
  }

  def freshAddresses(a: core.Account)(implicit ec: ExecutionContext): Future[Seq[core.Address]] = {
    a.getFreshPublicAddresses().map(_.asScala.toList)
  }

  def getAddressesInRange(from: Long, to: Long, a: core.Account)(implicit ec: ExecutionContext): Future[Seq[core.Address]] = {
    if (a.isInstanceOfBitcoinLikeAccount) {
      a.asBitcoinLikeAccount.getAddresses(from, to).map(_.asScala.toList)
    } else {
      Future.failed(new UnsupportedOperationException(s"Account type ${a.getWalletType} not supported for get addresses in range."))
    }
  }

  def sync(poolName: String, walletName: String, a: core.Account)(implicit ec: ExecutionContext): Future[SynchronizationResult] = {
    val promise: Promise[SynchronizationResult] = Promise[SynchronizationResult]()
    val receiver: core.EventReceiver = new SynchronizationEventReceiver(a.getIndex, walletName, poolName, promise)
    val synchronizationBus = a.synchronize()
    synchronizationBus.subscribe(LedgerCoreExecutionContext(ec), receiver)
    debug(s"Synchronize $a")
    val f = promise.future
    f onComplete (_ => synchronizationBus.unsubscribe(receiver))
    f
  }

  class Derivation(private val accountCreationInfo: core.AccountCreationInfo) {
    val index: Int = accountCreationInfo.getIndex

    lazy val view: AccountDerivationView = {
      val paths = accountCreationInfo.getDerivations.asScala
      val owners = accountCreationInfo.getOwners.asScala
      val pubKeys = {
        val pks = accountCreationInfo.getPublicKeys
        if (pks.isEmpty) {
          paths.map { _ => "" }
        }
        else {
          pks.asScala.map(HexUtils.valueOf)
        }
      }
      val chainCodes = {
        val ccs = accountCreationInfo.getChainCodes
        if (ccs.isEmpty) {
          paths.map { _ => "" }
        }
        else {
          ccs.asScala.map(HexUtils.valueOf)
        }
      }
      val derivations = paths.indices.map { i =>
        DerivationView(
          paths(i),
          owners(i),
          pubKeys(i) match {
            case "" => None
            case pubKey => Option(pubKey)
          },
          chainCodes(i) match {
            case "" => None
            case chainCode => Option(chainCode)
          })
      }
      AccountDerivationView(index, derivations)
    }
  }

  class ExtendedDerivation(info: core.ExtendedKeyAccountCreationInfo) {
    val index: Int = info.getIndex
    lazy val view: AccountExtendedDerivationView = {
      val extKeys = info.getExtendedKeys.asScala.map(Option.apply).padTo(info.getDerivations.size(), None)
      val derivations = (info.getDerivations.asScala, info.getOwners.asScala, extKeys).zipped map {
        case (path, owner, key) =>
          ExtendedDerivationView(path, owner, key)
      }
      AccountExtendedDerivationView(index, derivations.toList)
    }
  }

  def newDerivation(coreD: core.AccountCreationInfo): Derivation = {
    new Derivation(coreD)
  }

  def getDelegation(a: Account)(implicit ec: ExecutionContext): Future[Seq[DelegationView]] = {
    a.getWalletType match {
      case WalletType.TEZOS => a.asTezosLikeAccount().getCurrentDelegate()
        .filter(address => !address.isEmpty)
        .map(DelegationView.fromDelegatedAddress)
        .map(List(_))
        .recover { case _: java.util.NoSuchElementException => List[DelegationView]() }
      case _ => Future.successful(List())
    }
  }

}

case class AccountView(
                        @JsonProperty("wallet_name") walletName: String,
                        @JsonProperty("index") index: Int,
                        @JsonProperty("balance") balance: scala.BigInt,
                        @JsonProperty("operation_count") operationCounts: Map[core.OperationType, Int],
                        @JsonProperty("keychain") keychain: String,
                        @JsonProperty("currency") currency: CurrencyView,
                        @JsonProperty("status") status: SyncStatus
                      )

case class ERC20AccountView(
                             @JsonProperty("contract_address") contractAddress: String,
                             @JsonProperty("name") name: String,
                             @JsonProperty("number_of_decimal") numberOrDecimal: Int,
                             @JsonProperty("symbol") symbol: String,
                             @JsonProperty("balance") balance: scala.BigInt
                           )

object ERC20AccountView {
  def fromERC20Account(erc20Account: ERC20LikeAccount, bal: scala.BigInt): Future[ERC20AccountView] = {
    Future.successful(ERC20AccountView(
      erc20Account.getToken.getContractAddress,
      erc20Account.getToken.getName,
      erc20Account.getToken.getNumberOfDecimal,
      erc20Account.getToken.getSymbol,
      bal
    ))
  }
}

case class ERC20FullAccountView(
                                 @JsonProperty("wallet_name") walletName: String,
                                 @JsonProperty("index") index: Int,
                                 @JsonProperty("balance") balance: scala.BigInt,
                                 @JsonProperty("status") status: SyncStatus,
                                 @JsonProperty("contract_address") contractAddress: String,
                                 @JsonProperty("name") name: String,
                                 @JsonProperty("number_of_decimal") numberOrDecimal: Int,
                                 @JsonProperty("symbol") symbol: String
                               )

object ERC20FullAccountView {
  def fromERC20Account(
                        erc20Account: ERC20LikeAccount,
                        baseAccount: Account,
                        status: SyncStatus,
                        erc20Balance: scala.BigInt,
                        walletName: String
                      ): ERC20FullAccountView =
    ERC20FullAccountView(
      walletName,
      baseAccount.getIndex,
      erc20Balance,
      status,
      erc20Account.getToken.getContractAddress,
      erc20Account.getToken.getName,
      erc20Account.getToken.getNumberOfDecimal,
      erc20Account.getToken.getSymbol
    )
}

case class DerivationView(
                           @JsonProperty("path") path: String,
                           @JsonProperty("owner") owner: String,
                           @JsonProperty("pub_key") pubKey: Option[String],
                           @JsonProperty("chain_code") chainCode: Option[String]
                         )

case class AccountDerivationView(
                                  @JsonProperty("account_index") accountIndex: Int,
                                  @JsonProperty("derivations") derivations: Seq[DerivationView]
                                )

case class ExtendedDerivationView(
                                   @JsonProperty("path") path: String,
                                   @JsonProperty("owner") owner: String,
                                   @JsonProperty("extended_key") extKey: Option[String]
                                 )

case class AccountExtendedDerivationView(
                                          @JsonProperty("account_index") accountIndex: Int,
                                          @JsonProperty("derivations") derivations: Seq[ExtendedDerivationView]
                                        )

case class FreshAddressView(
                             @JsonProperty("address") address: String,
                             @JsonProperty("derivation_path") derivation: String
                           )

case class UTXOView(
                     @JsonProperty("hash") hash: String,
                     @JsonProperty("output_index") output_index: Long,
                     @JsonProperty("address") address: String,
                     @JsonProperty("height") height: Long,
                     @JsonProperty("confirmations") confirmations: Long,
                     @JsonProperty("amount") amount: scala.BigInt
                   )

object UTXOView {
  def fromBitcoinOutput(output: BitcoinLikeOutput, referenceBlockHeight: Long): UTXOView = {
    val confirmations: Long =
      if (output.getBlockHeight >= 0) referenceBlockHeight - output.getBlockHeight else output.getBlockHeight
    UTXOView(
      output.getTransactionHash,
      output.getOutputIndex,
      output.getAddress,
      output.getBlockHeight,
      confirmations,
      output.getValue.toBigInt.asScala)
  }
}

case class DelegationView(
                           @JsonProperty("address") address: String
                         )

object DelegationView {
  def fromDelegatedAddress(address: String): DelegationView = {
    DelegationView(address)
  }
}
