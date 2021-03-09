package co.ledger.wallet.daemon.models

import java.net.URL

import co.ledger.core
import co.ledger.core.implicits._
import co.ledger.core.{ConfigurationDefaults, ErrorCode, WalletPoolBuilder}
import co.ledger.wallet.daemon.async.MDCPropagatingExecutionContext.Implicits.global
import co.ledger.wallet.daemon.clients.ClientFactory
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.database.{PoolDto, RedisClientConfiguration, RedisPreferenceBackend}
import co.ledger.wallet.daemon.exceptions.{CoreDatabaseException, CurrencyNotFoundException, UnsupportedNativeSegwitException, WalletNotFoundException}
import co.ledger.wallet.daemon.libledger_core.async.LedgerCoreExecutionContext
import co.ledger.wallet.daemon.libledger_core.crypto.SecureRandomRNG
import co.ledger.wallet.daemon.libledger_core.logger.NoOpLogPrinter
import co.ledger.wallet.daemon.libledger_core.filesystem.ScalaPathResolver
import co.ledger.wallet.daemon.models.Wallet._
import co.ledger.wallet.daemon.schedulers.observers.SynchronizationResult
import co.ledger.wallet.daemon.services.LogMsgMaker
import co.ledger.wallet.daemon.utils.{HexUtils, Utils}
import com.fasterxml.jackson.annotation.JsonProperty
import com.twitter.inject.Logging
import com.typesafe.config.ConfigFactory
import org.bitcoinj.core.Sha256Hash

import scala.collection.JavaConverters._
import scala.collection._
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class Pool(private val coreP: core.WalletPool, val id: Long) extends Logging {
  private[this] val self = this

  private val _coreExecutionContext = LedgerCoreExecutionContext.operationPool
  private[this] val eventReceivers: mutable.Set[core.EventReceiver] = Utils.newConcurrentSet[core.EventReceiver]

  val name: String = coreP.getName
  logger.info(s"New Core Pool instance - $name - id $id")

  def view: Future[WalletPoolView] = coreP.getWalletCount().map { count => WalletPoolView(name, count) }

  /**
    * Obtain wallets by batch size and offset.
    *
    * @param offset the offset the query starts from.
    * @param batch  the size of query.
    * @return a tuple of total wallet count and a sequence of wallets from offset to batch size.
    */
  def wallets(offset: Int, batch: Int): Future[(Int, Seq[core.Wallet])] = {
    assert(offset >= 0, s"offset invalid $offset")
    assert(batch > 0, "batch must be positive")
    coreP.getWalletCount().flatMap { count =>
      val size = batch min (count - offset)
      coreP.getWallets(offset, size).map { coreWs =>
        coreWs.asScala.toList
      }.map((count, _))
    }
  }

  /**
    * Obtain ALL wallets.
    *
    * @return a sequence of wallet
    */
  def wallets: Future[Seq[core.Wallet]] = {
    coreP.getWalletCount().flatMap { count =>
      val batch = 20

      def walletsFromOffset(offset: Int): Future[List[core.Wallet]] = {
        val size = Math.min(batch, count - offset)
        if (size <= 0) {
          Future.successful(List.empty[core.Wallet])
        } else {
          for {
            fetchWallets <- coreP.getWallets(offset, size).map(_.asScala.toList)
            nextWallets <- walletsFromOffset(offset + batch)
          } yield fetchWallets ++ nextWallets
        }
      }

      walletsFromOffset(0)
    }
  }

  /**
    * Obtain wallet by name. If the name doesn't exist in local cache, a core retrieval will be performed.
    *
    * @param walletName name of wallet.
    * @return a future of optional wallet.
    */
  def wallet(walletName: String): Future[Option[core.Wallet]] = {
    coreP.getWallet(walletName).map(Option(_)).recover {
      case _: co.ledger.core.implicits.WalletNotFoundException => None
    }
  }

  /**
    * Obtain currency by name.
    *
    * @param currencyName the specified currency name.
    * @return a future of optional currency.
    */
  def currency(currencyName: String): Future[Option[core.Currency]] =
    coreP.getCurrency(currencyName).map(Option.apply).recover {
      case _: core.implicits.CurrencyNotFoundException => None
    }

  /**
    * Obtain currencies.
    *
    * @return future of currencies sequence.
    */
  def currencies(): Future[Seq[core.Currency]] = {
    coreP.getCurrencies().map(_.asScala.toList)
  }

  def addWalletIfNotExist(walletName: String, currencyName: String, isNativeSegwit: Boolean): Future[core.Wallet] = {
    coreP.getCurrency(currencyName).flatMap { coreC =>
      buildWalletConfig(coreC, isNativeSegwit) match {
        case Success(walletConfig) =>
          coreP.createWallet(walletName, coreC, walletConfig).flatMap { coreW =>
            info(LogMsgMaker.newInstance("Wallet created").append("name", walletName).append("pool_name", name).append("currency_name", currencyName).toString())
            Future.successful(coreW)
          }.recoverWith {
            case _: WalletAlreadyExistsException =>
              warn(LogMsgMaker.newInstance("Wallet already exist")
                .append("name", walletName)
                .append("pool_name", name)
                .append("currency_name", currencyName)
                .toString())
              coreP.getWallet(walletName).flatMap { coreW => Future.successful(coreW) }
          }

        case Failure(e) => Future.failed(e)
      }
    }.recoverWith {
      case _: core.implicits.CurrencyNotFoundException => Future.failed(CurrencyNotFoundException(currencyName))
    }
  }

  def updateWalletConfig(wallet: core.Wallet): Future[core.Wallet] = {
    val isNativeSegwit = Try(wallet.getConfiguration.getString("KEYCHAIN_ENGINE"))
      .map(_ == "BIP173_P2WPKH")
      .getOrElse(false)

    buildWalletConfig(wallet.getCurrency, isNativeSegwit) match {
      case Success(walletConfig) =>
        info(LogMsgMaker.newInstance("Updating wallet")
          .append("pool_name", name)
          .append("wallet_name", wallet.getName)
          .append("api_endpoint", walletConfig.getString("BLOCKCHAIN_EXPLORER_API_ENDPOINT"))
          .toString())
        coreP.updateWalletConfig(wallet.getName, walletConfig).flatMap { result =>
          info(LogMsgMaker.newInstance("Wallet update result")
            .append("pool_name", name)
            .append("wallet_name", wallet.getName)
            .append("result", result)
            .toString())
          result match {
            case ErrorCode.FUTURE_WAS_SUCCESSFULL => Future.successful(wallet)
            case _ => Future.failed(WalletNotFoundException(wallet.getName))
          }
        }

      case Failure(e) => Future.failed(e)
    }
  }

  /**
    * Subscribe specied event receiver to core pool, also save the event receiver to the local container.
    *
    * @param eventReceiver the event receiver object need to be registered.
    */
  def registerEventReceiver(eventReceiver: core.EventReceiver): Unit = {
    if (!eventReceivers.contains(eventReceiver)) {
      eventReceivers += eventReceiver
      coreP.getEventBus.subscribe(_coreExecutionContext, eventReceiver)
      debug(s"Register $eventReceiver")
    } else {
      debug(s"Already registered $eventReceiver")
    }
  }

  /**
    * Unsubscribe all event receivers for this pool, including empty the event receivers container in memory.
    *
    */
  def unregisterEventReceivers(): Unit = {
    eventReceivers.foreach { eventReceiver =>
      coreP.getEventBus.unsubscribe(eventReceiver)
      eventReceivers.remove(eventReceiver)
      debug(s"Unregister $eventReceiver")
    }
  }

  /**
    * Synchronize all accounts within this pool.
    *
    * @return a future of squence of synchronization results.
    */
  def sync(): Future[Seq[SynchronizationResult]] = {
    for {
      count <- coreP.getWalletCount()
      wallets <- coreP.getWallets(0, count)
      result <- Future.sequence(wallets.asScala.map { wallet => wallet.syncAccounts(name) }).map(_.flatten)
    } yield result
  }

  override def equals(that: Any): Boolean = {
    that match {
      case that: Pool => that.isInstanceOf[Pool] && self.hashCode == that.hashCode
      case _ => false
    }
  }

  override def hashCode: Int = {
    self.id.hashCode() + self.name.hashCode
  }

  override def toString: String = s"Pool(name: $name, id: $id)"

  private def buildWalletConfig(currency: core.Currency, isNativeSegwit: Boolean): Try[core.DynamicObject] = {
    val currencyName = currency.getName
    val hasNativeSegwitSupport = DaemonConfiguration.supportedNativeSegwitCurrencies.contains(currencyName)

    if (isNativeSegwit && !hasNativeSegwitSupport) {
      Failure(UnsupportedNativeSegwitException(currencyName))
    } else {
      val walletConfig = core.DynamicObject.newInstance()
      val apiUrl = DaemonConfiguration.explorer.api.paths.get(currencyName) match {
        case Some(path) =>
          path.explorerVersion match {
            case Some(version) =>
              walletConfig.putString("BLOCKCHAIN_EXPLORER_VERSION", version)
            case _ =>
          }
          new URL(s"${path.host}:${path.port}")
        case None => new URL(s"${ConfigurationDefaults.BLOCKCHAIN_DEFAULT_API_ENDPOINT}")
      }

      // Waiting for LLC-636 to be unified
      if (currencyName == "ripple") {
        walletConfig.putString("BLOCKCHAIN_EXPLORER_API_ENDPOINT", s"${apiUrl.getProtocol}://${apiUrl.getHost}")
        walletConfig.putString("BLOCKCHAIN_EXPLORER_PORT", apiUrl.getPort.toString)
      } else {
        walletConfig.putString("BLOCKCHAIN_EXPLORER_API_ENDPOINT", s"${apiUrl.getProtocol}://${apiUrl.getHost}:${apiUrl.getPort}")
      }

      val disableSyncToken: Boolean = DaemonConfiguration.explorer.api.paths.get(currencyName).exists(_.disableSyncToken)
      walletConfig.putBoolean("DEACTIVATE_SYNC_TOKEN", disableSyncToken)
      walletConfig.putInt("RIPPLE_LAST_LEDGER_SEQUENCE_OFFSET", DaemonConfiguration.rippleLastLedgerSequenceOffset)

      if (isNativeSegwit) {
        walletConfig.putString("KEYCHAIN_ENGINE", "BIP173_P2WPKH")
        walletConfig.putString("KEYCHAIN_DERIVATION_SCHEME", "84'/<coin_type>'/<account>'/<node>/<address>")
      }

      Success(walletConfig)
    }
  }
}

object Pool extends Logging {
  private val config = ConfigFactory.load()

  def newInstance(coreP: core.WalletPool, id: Long): Pool = {
    new Pool(coreP, id)
  }

  def newCoreInstance(poolDto: PoolDto): Future[core.WalletPool] = {
    val poolConfig = core.DynamicObject.newInstance()
    val builder = core.WalletPoolBuilder.createInstance()

    config.getString("user-preferences.storage") match {
      case "redis" => configureRedisAsPreferenceStorage(poolDto, builder)
      case "leveldb" => info("Using LevelDB as core preference database")
      case unknown: String => throw CoreDatabaseException(s"Failed to configure wallet daemon's core user-preferences database, unknown value ${unknown}")
    }

    Try(config.getString("core_database_engine")).toOption.getOrElse("sqlite3") match {
      case "postgres" =>
        info("Using PostgreSql as core database engine")
        val dbName = for {
          dbPort <- Try(config.getString("postgres.port"))
          dbHost <- Try(config.getString("postgres.host"))
          dbUserName <- Try(config.getString("postgres.username"))
          dbPwd <- Try(config.getString("postgres.password"))
          dbPrefix <- Try(config.getString("postgres.db_name_prefix"))
        } yield {
          // Ref: postgres://USERNAME:PASSWORD@HOST:PORT/DBNAME
          if (dbPwd.isEmpty) {
            (s"postgres://${dbUserName}@${dbHost}:${dbPort}/${dbPrefix}${poolDto.name}")
          } else {
            (s"postgres://${dbUserName}:${dbPwd}@${dbHost}:${dbPort}/${dbPrefix}${poolDto.name}")
          }
        }
        dbName match {
          case Success(cppUrl) =>
            val cnx = config.getInt("postgres.pool_size")
            poolConfig.putString("DATABASE_NAME", cppUrl)
            val backend = core.DatabaseBackend.getPostgreSQLBackend(cnx)
            builder.setDatabaseBackend(backend)
          case Failure(exception) =>
            throw CoreDatabaseException("Failed to configure wallet daemon's core database", exception)
        }
      case _ =>
        info("Using Sqlite as core database engine")
        builder.setDatabaseBackend(core.DatabaseBackend.getSqlite3Backend)
    }
    builder
      .setHttpClient(ClientFactory.httpCoreClient)
      .setWebsocketClient(ClientFactory.webSocketClient)
      .setLogPrinter(new NoOpLogPrinter(ClientFactory.threadDispatcher.getMainExecutionContext, true))
      .setThreadDispatcher(ClientFactory.threadDispatcher)
      .setPathResolver(new ScalaPathResolver(corePoolId(poolDto.userId, poolDto.name)))
      .setRandomNumberGenerator(new SecureRandomRNG)
      .setConfiguration(poolConfig)
      .setName(poolDto.name)
      .build()
  }

  private def configureRedisAsPreferenceStorage(poolDto: PoolDto, builder: WalletPoolBuilder) = {
    val maybeRedisConf = for {
      host <- Try(config.getString("redis.host"))
      port <- Try(config.getInt("redis.port"))
      dbPrefix <- Try(config.getString("postgres.db_name_prefix"))
    } yield {
      RedisClientConfiguration(
        dbPrefix = dbPrefix,
        poolName = poolDto.name,
        host = host,
        port = port,
        password = Try(config.getString("redis.password")).toOption,
        db = Try(config.getInt("redis.db")).toOption,
        connectionTimeout = Try(config.getInt("redis.connection_timeout")).toOption
      )
    }

    maybeRedisConf match {
      case Success(redisConfig) =>
        info(s"Using Redis as core preference database ${redisConfig.copy(password = Some("***"))}")
        val preferenceBackend = new RedisPreferenceBackend(redisConfig)
        preferenceBackend.init()
        builder.setExternalPreferencesBackend(preferenceBackend)
        builder.setInternalPreferencesBackend(preferenceBackend)
      case Failure(exception) =>
        throw CoreDatabaseException("Failed to configure wallet daemon's core user-preferences database", exception)
    }
  }

  private def corePoolId(userId: Long, poolName: String): String = HexUtils.valueOf(Sha256Hash.hash(s"$userId:$poolName".getBytes))
}

case class WalletPoolView(
                           @JsonProperty("name") name: String,
                           @JsonProperty("wallet_count") walletCount: Int
                         )

