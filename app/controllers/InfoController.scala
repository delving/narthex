package controllers

import javax.inject._
import scala.jdk.CollectionConverters._
import play.api.http.ContentTypes
import play.api.libs.json.{JsNull, Json, Writes}
import play.api.mvc.InjectedController
import com.codahale.metrics.health.{HealthCheck, HealthCheckRegistry}

import buildinfo.BuildInfo

/**
  * Endpoint for monitoring tools to read some basic info about this instance of the app
  */
@Singleton
class InfoController @Inject() ( // order of these explicit parameters does not matter
  healthCheckRegistry: HealthCheckRegistry
) extends InjectedController {

  def info = Action { request =>
    val result = Map(
      "version" -> BuildInfo.version,
      "scalaVersion" -> BuildInfo.scalaVersion,
      "sbtVersion" -> BuildInfo.sbtVersion,
      "gitCommitSha" -> "TODO" //BuildInfo.gitCommitSha
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
