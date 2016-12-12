package web

import buildinfo.BuildInfo
import com.codahale.metrics.health.{HealthCheck, HealthCheckRegistry}
import play.api.http.ContentTypes
import play.api.libs.json.{JsNull, Json, Writes}
import play.api.mvc.{Action, Controller}

import scala.collection.JavaConverters._

/**
  * Endpoint for monitoring tools to read some basic info about this instance of the app
  */
class InfoController(healthCheckRegistry: HealthCheckRegistry) extends Controller {

  def info = Action { request =>
    val result = Map(
      "version" -> BuildInfo.version,
      "scalaVersion" -> BuildInfo.scalaVersion,
      "sbtVersion" -> BuildInfo.sbtVersion,
      "gitCommitSha" -> BuildInfo.gitCommitSha
    )
    Ok(Json.toJson(result))
  }

  def health = Action { request =>
    implicit val resultWrites = new Writes[HealthCheck.Result]  {
      def writes(result: HealthCheck.Result) = Json.obj(
        "healthy" -> result.isHealthy,
        "message" -> result.getMessage,
        "error" -> { if (result.getError == null) JsNull else result.getError.getMessage() }
      )
    }

    val results = healthCheckRegistry.runHealthChecks().asScala
    Ok(Json.prettyPrint(Json.toJson(results))).as(ContentTypes.JSON)
  }
}
