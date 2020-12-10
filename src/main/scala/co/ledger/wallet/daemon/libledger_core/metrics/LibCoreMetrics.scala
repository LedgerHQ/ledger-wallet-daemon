package co.ledger.wallet.daemon.libledger_core.metrics

import co.ledger.core.{AllocationMetrics, DurationMetrics}

import scala.concurrent.{ExecutionContext, Future}


object LibCoreMetrics {

  case class AllocationMetric(objectType: String, numberOfInstances : Int)
  case class DurationMetric(recordName: String, totalMs: Long, counter: Long, average: Double)

  def fetchAllocations(implicit ec: ExecutionContext): Future[List[AllocationMetric]] = Future {

    import scala.collection.JavaConverters._

    val allocations = AllocationMetrics.getObjectAllocations.asScala

    allocations.map(e => AllocationMetric(e._1, e._2))
      .toList
      .filter(_.numberOfInstances >= 50)
      .sortBy(_.numberOfInstances)
  }

  def fetchDurations(implicit ec: ExecutionContext): Future[List[DurationMetric]] = Future {
    import scala.collection.JavaConverters._

    DurationMetrics.getAllDurationMetrics.asScala.toList.map({
      case (key, metric) =>
        DurationMetric(key, metric.getTotalMs, metric.getCount, metric.getTotalMs.toDouble / metric.getCount.toDouble)
    })
  }

}
