package co.ledger.wallet.daemon.clients

import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.utils.NetUtils.Host
import co.ledger.wallet.daemon.utils.Utils
import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache, RemovalNotification}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.service.{Backoff, RetryBudget}
import com.twitter.finagle.{Http, Service}
import com.twitter.inject.Logging
import com.twitter.util.{Duration, StorageUnit}

import scala.util.Try

class ScalaHttpClientPool extends Logging {

  import ScalaHttpClientPool._

  // FIXME : Make configurable cache pool ttl and cache size
  private val connectionPools: LoadingCache[Host, Service[Request, Response]] =
    CacheBuilder.newBuilder()
      .maximumSize(50)
      .removalListener((notification: RemovalNotification[Host, Service[Request, Response]]) => {
        info(s"Connection pool for Host ${notification.getKey} has been removed from cache due to " +
          s"${notification.getCause.name} cause, was evicted = ${notification.wasEvicted}")
        notification.getValue.close()
      })
      .build[Host, Service[Request, Response]](new CacheLoader[Host, Service[Request, Response]] {
        def load(host: Host): Service[Request, Response] = {
          serviceFor(host) match {
            case Right(service) => service
            case Left(th) => throw th
          }
        }
      })

  def poolCacheSize: Long = connectionPools.size()

  def isHostCached(host: Host): Boolean = connectionPools.asMap().containsKey(host)

  protected[clients] def serviceForHost(host: Host): Service[Request, Response] = connectionPools.get(host)

  def execute(host: Host, request: Request): com.twitter.util.Future[Response] =
    serviceForHost(host)(request).map(response => {
      info(s"Received from ${request.host.getOrElse("No host")} - ${request.uri} status=${response.status.code} " +
        s" - statusText=${response.status.reason} - " +
        s"Request : $request - (Payload : ${Utils.preview(request.getContentString(), 1000)}) - " +
        s"Response : $response - (Payload : ${Utils.preview(response.getContentString(), 1000)})")
      response
    }).onFailure(th => error(s"Failed to execute request on host $host with request $request. Error message : ${th.getMessage}", th))
}

object ScalaHttpClientPool extends Logging {
  val PROXY_BUFFER_SIZE: Int = 4 * 4096

  private val budget: RetryBudget = RetryBudget(
    ttl = Duration.fromSeconds(DaemonConfiguration.explorer.client.retryTtl),
    minRetriesPerSec = DaemonConfiguration.explorer.client.retryMin,
    percentCanRetry = DaemonConfiguration.explorer.client.retryPercent
  )
  // FIXME Client sharing ?
  private val client = Http.client
    .withRetryBudget(budget)
    .withMaxResponseSize(StorageUnit.fromBytes(Int.MaxValue))
    .withRetryBackoff(Backoff.linear(
      Duration.fromMilliseconds(DaemonConfiguration.explorer.client.retryBackoff),
      Duration.fromMilliseconds(DaemonConfiguration.explorer.client.retryBackoff)))
    .withSessionQualifier.noFailFast
    .withSessionPool.maxSize(DaemonConfiguration.explorer.client.connectionPoolSize)
    .withSessionPool.ttl(Duration.fromSeconds(DaemonConfiguration.explorer.client.connectionTtl))

  def serviceFor(host: Host): Either[Throwable, Service[Request, Response]] = {

    Try(proxyFor(host) match {
      case Some(proxy) => tls(host, client).withTransport.httpProxyTo(s"${host.hostName}:${host.port}")
        .newService(s"${proxy.host}:${proxy.port}")
      case None => tls(host, client).newService(s"${host.hostName}:${host.port}")
    }).toEither
  }

  def tls(host: Host, client: Http.Client): Http.Client =
    host.protocol match {
      case "https" => client.withTls(host.hostName)
      case _ => client
    }


  def isOnError(statusCode: Int): Boolean = {
    !(statusCode >= 200 && statusCode < 400)
  }

  def proxyFor(host: Host): Option[DaemonConfiguration.Proxy] = {
    DaemonConfiguration.explorer.api.proxyUse.get(host) match {
      case Some(false) | None =>
        info(s"No Proxy will be used for $host")
        None
      case _ => Some(DaemonConfiguration.proxy)
    }
  }
}
