package init.healthchecks

import com.codahale.metrics.health.HealthCheck
import com.codahale.metrics.health.HealthCheck.Result
import triplestore.TripleStore

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.{Failure, Success}

class FusekiHealthCheck(tripleStore: TripleStore, timeoutMillis: Long) extends HealthCheck{

  override def check(): Result = {
    val duration = timeoutMillis.milliseconds
    Await.ready(tripleStore.ask("Ask {?s ?p ?o}"), duration).value.get match {
      case Success(_) => Result.healthy(s"Responded within $duration")
      case Failure(e) => Result.unhealthy(s"Could not reach underlying store within $duration. Exception: $e")
    }
  }
}
