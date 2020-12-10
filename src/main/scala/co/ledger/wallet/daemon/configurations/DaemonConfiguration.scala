package co.ledger.wallet.daemon.configurations

import java.net.URL

import co.ledger.wallet.daemon.utils.NetUtils
import co.ledger.wallet.daemon.utils.NetUtils.Host
import com.twitter.inject.Logging
import com.typesafe.config.ConfigFactory
import slick.jdbc.JdbcProfile

import scala.collection.JavaConverters._
import scala.util.Try

object DaemonConfiguration extends Logging {
  private val config = ConfigFactory.load()
  private val DEFAULT_SYNC_INTERVAL: Int = 60 // 60 seconds
  private val DEFAULT_RESYNC_CHECK_INTERVAL: Int = 3 // 5 seconds
  private val DEFAULT_SYNC_ON_GOING: Int = 2 // 2 synchronizations at a time
  private val DEFAULT_SYNC_ACCOUNT_REGISTER_INTERVAL: Int = 600 // 10 mins
  // Default value for keeping alive connections inside the client connection pool
  private val DEFAULT_CLIENT_CONNECTION_TTL: Int = 120 // 120 seconds
  // Retry policy inside a connection pool
  private val DEFAULT_CLIENT_CONNECTION_RETRY_TTL: Int = 3 // Seconds to make retries
  private val DEFAULT_CLIENT_CONNECTION_RETRY_MIN: Int = 5 // Number of minimum retries per second
  private val DEFAULT_CLIENT_CONNECTION_RETRY_PERCENT: Double = 1.0D // 100% of queries would be retried at least one time
  private val DEFAULT_CLIENT_CONNECTION_RETRY_BACKOFF: Int = 50 // Linear Backoff policy delta ms
  private val DEFAULT_CORE_POOL_THREADS_FACTOR: Int = 4 // Default value for allocated threads per pool for core lib

  /*
   * We set the value of RIPPLE_LAST_LEDGER_SEQUENCE_OFFSET to be large enough
   * for use in Vault, but not infinity.
   *
   * The value we use if equal to the number of valid XRP Sequences produced in
   * one year, assuming a Sequence finality time of 3 seconds.
   *
   * See https://ledgerhq.atlassian.net/browse/LLC-346 for more information.
   */
  private val RIPPLE_LAST_LEDGER_SEQUENCE_OFFSET: Int = 10512000

  val proxy: Proxy = {
    val p = Proxy(config.getString("proxy.host"), config.getInt("proxy.port"))
    info(s"[Proxy Configuration] - ${p.host}:${p.port} - enabled = ${config.getBoolean("proxy.enabled")}")
    p
  }


  val supportedNativeSegwitCurrencies: List[String] = if (config.hasPath("native_segwit_currencies")) {
    config.getStringList("native_segwit_currencies").asScala.toList
  } else List[String]()

  val adminUsers: Seq[(String, String)] = if (config.hasPath("default_users")) {
    val usersConfig = config.getConfigList("default_users").asScala
    for {
      userConfig <- usersConfig
    } yield (userConfig.getString("username"), userConfig.getString("password"))

  } else {
    List[(String, String)]()
  }

  val dbProfileName: String = Try(config.getString("database_engine")).toOption.getOrElse("sqlite3")

  val dbProfile: JdbcProfile = dbProfileName match {
    case "sqlite3" =>
      slick.jdbc.SQLiteProfile
    case "postgres" =>
      slick.jdbc.PostgresProfile
    case "h2mem1" =>
      slick.jdbc.H2Profile
    case others => throw new Exception(s"Unknown database backend $others")
  }

  object Synchronization {
    val syncInterval: Int = if (config.hasPath("synchronization.sync_interval_in_seconds")) {
      config.getInt("synchronization.sync_interval_in_seconds")
    }
    else {
      DEFAULT_SYNC_INTERVAL
    }
    val syncAccountRegisterInterval: Int = if (config.hasPath("synchronization.sync_account_register_interval_in_seconds")) {
      config.getInt("synchronization.sync_account_register_interval_in_seconds")
    }
    else {
      DEFAULT_SYNC_ACCOUNT_REGISTER_INTERVAL
    }
    val resyncCheckInterval: Int = if (config.hasPath("synchronization.resync_check_interval_in_seconds")) {
      config.getInt("synchronization.resync_check_interval_in_seconds")
    }
    else {
      DEFAULT_RESYNC_CHECK_INTERVAL
    }
    val maxOnGoing: Int = if (config.hasPath("synchronization.max_sync_ongoing")) {
      config.getInt("synchronization.max_sync_ongoing")
    }
    else {
      DEFAULT_SYNC_ON_GOING
    }
  }

  val realTimeObserverOn: Boolean =
    if (config.hasPath("realtimeobservation")) {
      config.getBoolean("realtimeobservation")
    }
    else {
      false
    }

  // The expire time in minutes of the pagination token used for querying operations
  val paginationTokenTtlMin: Int =
    if (config.hasPath("pagination_token.ttl_min")) {
      config.getInt("pagination_token.ttl_min")
    }
    else {
      3
    }

  // The maximum size of pagination token cache
  val paginationTokenMaxSize: Long =
    if (config.hasPath("pagination_token.ttl_min")) {
      config.getInt("pagination_token.max_size")
    }
    else {
      1000 * 1000
    }

  // The expire time in minutes of the balance per account
  val balanceCacheTtlMin: Int =
    if (config.hasPath("caching.balance.ttl_minute")) {
      config.getInt("caching.balance.ttl_minute")
    }
    else {
      1
    }

  // The maximum size of pagination token cache
  val balanceCacheMaxSize: Long =
    if (config.hasPath("caching.balance.max_size")) {
      config.getInt("caching.balance.max_size")
    }
    else {
      1000
    }

  val coreDbConfig = (for {
    dbPort <- Try(config.getString("postgres.port"))
    dbHost <- Try(config.getString("postgres.host"))
    dbUserName <- Try(config.getString("postgres.username"))
    dbPwd <- Try(config.getString("postgres.password"))
    dbPrefix <- Try(config.getString("postgres.core.db_name_prefix"))
    maxCnx <- Try(config.getInt("postgres.core.pool_size"))
  } yield CoreDbConfig(dbHost, dbPort, dbUserName, dbPwd, dbPrefix, maxCnx))
    .get // We have to fail fast if configuration is missing

  // The core pool operation size
  val corePoolOpSizeFactor: Int =
    if (config.hasPath("core.ops_threads_factor")) {
      config.getInt("core.ops_threads_factor")
    }
    else DEFAULT_CORE_POOL_THREADS_FACTOR

  val isPrintCoreLibLogsEnabled: Boolean = config.hasPath("debug.print_core_logs") && config.getBoolean("debug.print_core_logs")

  lazy val coreDataPath: String = Try(config.getString("core.core_data_path")).getOrElse("./core_data")

  val explorer: ExplorerConfig = {
    val explorer = config.getConfig("explorer")
    val api = explorer.getConfig("api")
    val connectionPoolSize = api.getInt("connection_pool_size")
    val fallbackTimeout = api.getInt("fallback_timeout")
    val retryTtl = if (api.hasPath("client_connection_retry_ttl")) {
      api.getInt("client_connection_retry_ttl")
    } else {
      DEFAULT_CLIENT_CONNECTION_RETRY_TTL
    }
    val retryMin = if (api.hasPath("client_connection_retry_min")) {
      api.getInt("client_connection_retry_min")
    } else {
      DEFAULT_CLIENT_CONNECTION_RETRY_MIN
    }
    val retryPercent = if (api.hasPath("client_connection_retry_percent")) {
      api.getDouble("client_connection_retry_percent")
    } else {
      DEFAULT_CLIENT_CONNECTION_RETRY_PERCENT
    }
    val retryBackoffDelta = if (api.hasPath("client_connection_retry_backoff_delta")) {
      api.getInt("client_connection_retry_backoff_delta")
    } else {
      DEFAULT_CLIENT_CONNECTION_RETRY_BACKOFF
    }
    val connectionPoolTtl = if (api.hasPath("client_connection_connection_pool_ttl")) {
      api.getInt("client_connection_connection_ttl")
    } else {
      DEFAULT_CLIENT_CONNECTION_TTL
    }
    val explorerInfos = api.getConfigList("paths").asScala.toList.map { path =>
      val currency = path.getString("currency")
      val host = path.getString("host")
      val port = path.getInt("port")
      val fallback = Try(path.getString("fallback")).toOption
      val explorerVersion = Try(path.getString("explorer_version")).toOption
      val disableSyncToken = Try(path.getBoolean("disable_sync_token")).getOrElse(false)
      val feesPath = Try(path.getString("fees_path")).toOption.map(FeesPath)
      currency -> PathConfig(host, port, disableSyncToken, fallback, explorerVersion, feesPath)
    }.toMap

    val proxyUseMap = api.getConfigList("paths").asScala.toList.map { path =>
      val host = path.getString("host").trim
      val port = path.getInt("port")
      val url = new URL(s"$host:$port")
      // Use proxy if proxy is enabled globally and proxyuse is true or undefined
      val proxyUse = Try(path.getBoolean("proxyuse")).getOrElse(false)
      NetUtils.urlToHost(url) -> proxyUse
    }.toMap

    ExplorerConfig(
      ApiConfig(fallbackTimeout, explorerInfos, proxyUseMap),
      ClientConnectionConfig(connectionPoolSize, retryBackoffDelta, connectionPoolTtl, retryTtl, retryMin, retryPercent))
  }


  val ETH_SLOW_FEES_FACTOR: Double = Try(config.getDouble("ethereum.feesfactor.slow")).getOrElse(0.75)
  val ETH_NORMAL_FEES_FACTOR: Double = Try(config.getDouble("ethereum.feesfactor.normal")).getOrElse(1.0)
  val ETH_FAST_FEES_FACTOR: Double = Try(config.getDouble("ethereum.feesfactor.fast")).getOrElse(1.25)
  /**
    * We are facing frequent gas limit too low issue on smart contract interactions
    * (see : https://ledgerhq.atlassian.net/browse/BACK-831)
    * This is probably due to rounded value from RPC node to our explorers due to conversion issue.
    * We expose here ability to configure factor for estimated gas limit amplification
    */
  val ETH_SMART_CONTRACT_GAS_LIMIT_FACTOR: Double = Try(config.getDouble("ethereum.gaslimitfactor")).getOrElse(2)
  val TEZOS_NODE: String = Try(config.getString("tezos.node.host")).getOrElse("https://xtz-node.api.live.ledger.com")

  val rippleLastLedgerSequenceOffset: Int = {
    if (config.hasPath("ripple_last_ledger_sequence_offset")) {
      config.getInt("ripple_last_ledger_sequence_offset")
    }
    else {
      RIPPLE_LAST_LEDGER_SEQUENCE_OFFSET
    }
  }

  val rabbitMQUri: Try[String] = Try(config.getString("rabbitmq.uri"))

  val IS_DD_ENABLED: Boolean = config.getString("datadog.agent_host") match {
    case "" => false
    case _ => true
  }

  val DD_HOST: String = config.getString("datadog.agent_host")
  val DD_PORT: String = config.getString("datadog.agent_port")

  val DD_TRACE_PREFIX: String = config.getString("datadog.ddtrace_prefix")
  val DD_TRACE_PORT: Int = config.getInt("datadog.ddtrace_port")

  case class ApiConfig(fallbackTimeout: Int, paths: Map[String, PathConfig], proxyUse: Map[Host, Boolean])

  case class PathConfig(host: String, port: Int, disableSyncToken: Boolean, fallback: Option[String], explorerVersion: Option[String], feesPath: Option[FeesPath])

  case class ClientConnectionConfig(connectionPoolSize: Int, // Maximum concurrent connection inside a connection pool
                                    retryBackoff: Int, // In millis
                                    connectionTtl: Int, // Seconds
                                    retryTtl: Int, // Seconds
                                    retryMin: Int, // minimum retry per second
                                    retryPercent: Double)

  case class ExplorerConfig(api: ApiConfig, client: ClientConnectionConfig)

  case class Proxy(host: String, port: Int)

  case class FeesPath(path: String)

  case class SynchronizationConfig(delay: Int, frequency: Int)

  case class CoreDbConfig(dbHost: String, dbPort: String, dbUserName: String, dbPwd: String, dbPrefix: String, cnxPoolSize: Int)

}
