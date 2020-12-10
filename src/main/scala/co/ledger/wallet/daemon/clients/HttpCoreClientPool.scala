package co.ledger.wallet.daemon.clients

import java.io.BufferedInputStream
import java.net.URL
import java.util

import co.ledger.core._
import co.ledger.wallet.daemon.exceptions.InvalidUrlException
import co.ledger.wallet.daemon.utils.NetUtils
import co.ledger.wallet.daemon.utils.Utils.RichTwitterFuture
import com.sun.xml.internal.messaging.saaj.util.ByteOutputStream
import com.twitter.finagle.http.{Method, RequestBuilder, Response}
import com.twitter.inject.Logging
import com.twitter.io.Buf

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class HttpCoreClientPool(val ec: ExecutionContext, client: ScalaHttpClientPool) extends co.ledger.core.HttpClient with Logging {

  import ScalaHttpClientPool._

  implicit val executionContext: ExecutionContext = ec

  override def execute(httpCoreRequest: HttpRequest): Unit =
    Future(Try(new URL(httpCoreRequest.getUrl)).map(url => (url, NetUtils.urlToHost(url))))
      .flatMap {
        case Success((url, host)) =>
          val headers = Map(
            "User-Agent" -> "ledger-lib-core",
            "Content-Type" -> "application/json"
          ) ++ httpCoreRequest.getHeaders.asScala.toMap
          val req = RequestBuilder()
            .url(url)
            .addHeaders(headers)
            .build(resolveMethod(httpCoreRequest.getMethod), content(httpCoreRequest))

          client.execute(host, req)
            .map { response =>
              debug(s"Core Http received from ${httpCoreRequest.getUrl} status=${response.status.code} " +
                s"- statusText=${response.status.reason}")
              new ScalaHttpUrlConnection(
                response.status.code,
                response.status.reason,
                getResponseHeaders(response),
                readResponseBody(response, isOnError(response.status.code)))
            }
            .map(r => {
              httpCoreRequest.complete(r, null)
            }).asScala()
        case Failure(exception) => Future(httpCoreRequest.complete(null, new Error(ErrorCode.HTTP_ERROR, s"Failed to parse url ${httpCoreRequest.getUrl} => ${exception.getMessage}")))
      }.andThen { case _ => httpCoreRequest.destroy() }

  private def content(coreRequest: HttpRequest): Option[Buf] =
    if (coreRequest.getBody.nonEmpty) Some(Buf.ByteArray.Owned(coreRequest.getBody))
    else None

  private def getResponseHeaders(response: Response) = {
    val headers = new util.HashMap[String, String]()
    for ((key, value) <- response.headerMap) {
      headers.put(key, value)
    }
    headers
  }

  private def readResponseBody(resp: Response, onError: Boolean): HttpReadBodyResult = {
    val response = new BufferedInputStream(resp.getInputStream)
    val buffer = new Array[Byte](PROXY_BUFFER_SIZE)
    val outputStream = new ByteOutputStream()
    try {
      var size = 0
      do {
        size = response.read(buffer)
        if (size < buffer.length) {
          outputStream.write(buffer.slice(0, size))
        } else {
          outputStream.write(buffer)
        }
      } while (size > 0)
      val data = outputStream.getBytes
      if (onError) error(s"HTTP call is on error. Received content : (${resp.getContentString()}) ")
      new HttpReadBodyResult(null, data)
    } catch {
      case t: Throwable =>
        logger.error("Failed to read response body", t)
        val error = new co.ledger.core.Error(ErrorCode.HTTP_ERROR, "An error happened during body reading.")
        new HttpReadBodyResult(error, null)
    } finally {
      outputStream.close()
      response.close()
    }
  }

  private def resolveMethod(method: HttpMethod): Method = method match {
    case HttpMethod.GET => Method.Get
    case HttpMethod.POST => Method.Post
    case HttpMethod.PUT => Method.Put
    case HttpMethod.DEL => Method.Delete
    case _ => throw InvalidUrlException(s"Unsupported method ${method.name()}")
  }

  def poolCacheSize: Long = client.poolCacheSize

}
