package monitoring

import com.kenshoo.play.metrics.{Metrics, MetricsFilterImpl}
import play.api.http.Status

class MyMetricsFilter(val metrics: Metrics) extends MetricsFilterImpl(metrics) {

  // configure metrics prefix
  override def labelPrefix: String = "narthex"

  // configure status codes to be monitored. other status codes are labeled as "other"
  override def knownStatuses = Seq(Status.OK)
}
