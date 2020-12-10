package co.ledger.wallet.daemon.controllers


import co.ledger.core.LedgerCore
import co.ledger.wallet.daemon.BuildInfo
import co.ledger.wallet.daemon.async.MDCPropagatingExecutionContext
import co.ledger.wallet.daemon.clients.{ApiClient, ClientFactory}
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.libledger_core.metrics.LibCoreMetrics
import co.ledger.wallet.daemon.libledger_core.metrics.LibCoreMetrics.{AllocationMetric, DurationMetric}
import co.ledger.wallet.daemon.models.AccountInfo
import co.ledger.wallet.daemon.services.{AccountsService, SyncStatus, Syncing}
import com.twitter.finagle.http.Request
import com.twitter.finatra.http.Controller
import javax.inject.Inject

class StatusController @Inject()(accountsService: AccountsService) extends Controller {

  import StatusController._

  /**
    * End point queries for the version of core library currently used.
    *
    */
  get("/_health") { request: Request =>
    info(s"GET _health $request")
    response.ok(Status(LedgerCore.getStringVersion))
  }

  get("/_version") { request: Request =>
    info(s"GET _version $request")
    response.ok(
      VersionResponse(
        BuildInfo.name, BuildInfo.version, BuildInfo.scalaVersion,
        BuildInfo.commitHash.getOrElse("unknown"), LedgerCore.getStringVersion, DaemonConfiguration.explorer))
  }

  get("/_metrics") { request: Request =>
    info(s"GET _metrics $request")
    import MDCPropagatingExecutionContext.Implicits.global
    accountsService.ongoingSyncs()
      .map(ongoingSyncs =>
        MetricsResponse(
          ClientFactory.httpCoreClient.poolCacheSize,
          ApiClient.fallbackServices.poolCacheSize,
          ApiClient.feeServices.poolCacheSize,
          OnGoingSyncs(ongoingSyncs.count(_._2.isInstanceOf[Syncing]), ongoingSyncs))
      )

  }

  get("/_metrics/libcore") { request: Request =>
    info(s"GET _metrics/libcore $request")
    import MDCPropagatingExecutionContext.Implicits.global
    LibCoreMetrics.fetchAllocations.flatMap({ allocations =>
      LibCoreMetrics.fetchDurations.map((allocations, _))
    }).map((LibCoreMetricsResponse.apply _).tupled)
  }

}

object StatusController {

  case class Status(engine_version: String, status: String = "OK")

  case class VersionResponse(name: String, version: String, scalaVersion: String, commitHash: String, libcoreVersion: String, explorers: DaemonConfiguration.ExplorerConfig)

  case class MetricsResponse(coreHttpCachedPool: Long, feesHttpCachedPool: Long, fallbackHttpCachedPool: Long, ongoingSyncs: OnGoingSyncs)

  case class LibCoreMetricsResponse(allocations: List[AllocationMetric], durations: List[DurationMetric])

  case class OnGoingSyncs(total: Integer, detail: List[(AccountInfo, SyncStatus)])
}
