package init

import scala.jdk.CollectionConverters._
import java.util.concurrent.TimeUnit
import javax.inject._
import play.api.{Configuration, Logging}
import com.codahale.metrics.MetricRegistry
import org.coursera.metrics.datadog.DatadogReporter
import org.coursera.metrics.datadog.transport.UdpTransport

import buildinfo.BuildInfo

@Singleton
class NarthexDatadog @Inject()(
    config: Configuration,
    metrics: MetricRegistry
) extends Logging {

  val datadogEnabledConfigProp = "datadog.enabled"
  val datadogStatsdPortConfigProp = "datadog.statsdPort"
  val datadogStatsDHostConfigProp = "datadog.statsdHost"
  val datadogIntervalConfigProp = "datadog.reportingIntervalInSeconds"

  case class DatadogReportingConfig(intervalInSeconds: Long, statsdHost: String, statsdPort: Int)

  val datadogEnabled = config.getOptional[Boolean](datadogEnabledConfigProp)
    .getOrElse(throw new RuntimeException(s"Mandatory configprop ${datadogEnabledConfigProp} not set"))

  val datadogConfigOpt: Option[DatadogReportingConfig] =
    if (datadogEnabled) {
      val interval = config.getOptional[Long](datadogIntervalConfigProp).getOrElse(
        throw new RuntimeException(s"Mandatory configprop ${datadogIntervalConfigProp} not set"))

      val host = config.getOptional[String](datadogStatsDHostConfigProp)
        .getOrElse("localhost")
      val port = config.getOptional[Int](datadogStatsdPortConfigProp).getOrElse(8125)
      Some(DatadogReportingConfig(interval, host, port))
    } else {
      None
    }

  private def initReporter(configOpt: Option[DatadogReportingConfig], registry: MetricRegistry): Option[DatadogReporter] = {
    configOpt match {
      case None => {
        None
      }
      case Some(config) => {
        val tags = List("narthex", s"v${BuildInfo.version}")
        // Removed from tags because gitCommitSha isn't available anymore: "sha-${BuildInfo.gitCommitSha}"

        val transport = new UdpTransport.Builder()
          .withPort(config.statsdPort)
          .withStatsdHost(config.statsdHost)
          .build()

        val reporter = DatadogReporter.forRegistry(registry)
          .withTransport(transport)
          .withTags(tags.asJava)
          .withPrefix("narthex")
          .build()
        reporter.start(config.intervalInSeconds, TimeUnit.SECONDS)
        logger.info(s"Started Datadog reporter, reporting interval: ${config.intervalInSeconds} seconds")
        Some(reporter)
      }
    }
  }

  val reporterOpt = initReporter(datadogConfigOpt, metrics)

}
