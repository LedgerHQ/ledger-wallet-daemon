package co.ledger.wallet.daemon.api

import co.ledger.wallet.daemon.controllers.StatusController.VersionResponse
import co.ledger.wallet.daemon.utils.APIFeatureTest
import com.twitter.finagle.http.Status
import co.ledger.wallet.daemon.utils.NetUtils.Host
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.{DeserializationContext, JsonDeserializer, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper

class StatusApiTest extends APIFeatureTest {

  class HostDeserializer extends JsonDeserializer[Map[Host, Boolean]] {
    private val mapper: ObjectMapper = new ObjectMapper() with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)

    override def deserialize(jp: JsonParser, ctxt: DeserializationContext): Map[Host, Boolean] = {
      Map(Host("host", "protoc", 1234) -> true)
    }
  }

  val moduleHost: SimpleModule = new SimpleModule
  moduleHost.addDeserializer(classOf[Map[Host, Boolean]], new HostDeserializer)
  server.mapper.registerModule(moduleHost)

  test("_version endpoint is returning buildinfo") {
    val versionResponse = parse[VersionResponse](server.httpGet("/_version", andExpect = Status.Ok))
    assert(isValidVersion(versionResponse.libcoreVersion), versionResponse.libcoreVersion)
    assert(isValidVersion(versionResponse.version))
    assert(isValidVersion(versionResponse.scalaVersion))
    assert(isValidSHA(versionResponse.commitHash))
    assert(versionResponse.name == "wallet-daemon")
  }

  test("is valid version checker") {
    assert(isValidVersion("3.3.0"))
    assert(isValidVersion("3.3.0-86df3"))
    assert(!isValidVersion("0.0.0.0"))
    assert(!isValidVersion("0.0.0"))
    assert(!isValidVersion(""))
  }

  test("is valid sha checker") {
    assert(isValidSHA("dc0a1b846b1aba65989704f194fdfb5f3e8a8a22"))
    assert(!isValidSHA("unknown"))
  }

  def isValidVersion(version: String): Boolean = {
    // version has no blank spaces
    version.size == version.trim.size &&
      // Version is not only 0.0.0
      Some(version).map(_.replaceAll("[0.]", "")).exists(!_.isEmpty)
  }

  def isValidSHA(sha: String): Boolean = {
    sha.matches("[a-fA-F0-9]{40}")
  }
}
