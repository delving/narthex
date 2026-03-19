package controllers

import javax.inject._
import scala.jdk.CollectionConverters._
import scala.util.Try
import play.api.http.ContentTypes
import play.api.libs.json.{JsNull, JsObject, Json, Writes}
import play.api.mvc.InjectedController
import com.codahale.metrics.health.{HealthCheck, HealthCheckRegistry}

import buildinfo.BuildInfo
import init.NarthexConfig
import services.{GlobalDatabaseService, GlobalDsInfoService, GlobalOaiSourceRepository, GlobalMappingMetadataRepository}
import triplestore.{TripleStore, Fuseki}

/**
  * Endpoint for monitoring tools to read some basic info about this instance of the app
  */
@Singleton
class InfoController @Inject() (
  healthCheckRegistry: HealthCheckRegistry,
  tripleStore: TripleStore,
  narthexConfig: NarthexConfig
) extends InjectedController {

  def info = Action { request =>
    val result = Map(
      "version" -> BuildInfo.version,
      "scalaVersion" -> BuildInfo.scalaVersion,
      "sbtVersion" -> BuildInfo.sbtVersion,
      "gitCommitSha" -> BuildInfo.commitSha
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

  def connectionStats = Action { request =>
    tripleStore match {
      case fuseki: Fuseki => 
        val (active, total, failed) = fuseki.getConnectionStats
        val failureRate = if (total > 0) Math.round((failed.toDouble / total * 100) * 100.0) / 100.0 else 0.0
        Ok(Json.obj(
          "activeConnections" -> active,
          "totalRequests" -> total,
          "failedRequests" -> failed,
          "failureRate" -> failureRate
        )).as(ContentTypes.JSON)
      case _ => 
        Ok(Json.obj("error" -> "Connection stats not available for this triple store implementation")).as(ContentTypes.JSON)
    }
  }

  /** PostgreSQL diagnostic endpoint — shows connection status, schema version, and table counts. */
  def postgresStatus = Action { request =>
    GlobalDatabaseService.get() match {
      case None =>
        Ok(Json.prettyPrint(Json.obj(
          "configured" -> false,
          "readEnabled" -> narthexConfig.postgresReadEnabled,
          "message" -> "narthex.postgres section not present in configuration"
        ))).as(ContentTypes.JSON)

      case Some(dbService) =>
        val status = Try {
          val conn = dbService.getConnection()
          try {
            val healthy = conn.isValid(5)

            // Flyway schema version
            val schemaVersion = {
              val stmt = conn.createStatement()
              try {
                val rs = stmt.executeQuery(
                  "SELECT version, description FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"
                )
                try {
                  if (rs.next()) Json.obj(
                    "version" -> rs.getString("version"),
                    "description" -> rs.getString("description")
                  )
                  else Json.obj("version" -> "unknown")
                } finally rs.close()
              } finally stmt.close()
            }

            // HikariCP pool stats
            val poolStats = dbService.getDataSource match {
              case hikari: com.zaxxer.hikari.HikariDataSource =>
                val pool = hikari.getHikariPoolMXBean
                if (pool != null) Json.obj(
                  "activeConnections" -> pool.getActiveConnections,
                  "idleConnections" -> pool.getIdleConnections,
                  "totalConnections" -> pool.getTotalConnections,
                  "threadsAwaitingConnection" -> pool.getThreadsAwaitingConnection
                )
                else Json.obj("status" -> "pool MXBean not available")
              case _ => Json.obj("status" -> "not HikariCP")
            }

            // Row counts for key tables
            val tables = Seq(
              "datasets", "dataset_state", "dataset_harvest_config",
              "dataset_harvest_schedule", "dataset_mapping_config", "dataset_indexing",
              "oai_sources", "oai_source_set_counts",
              "default_mappings", "default_mapping_versions",
              "dataset_mappings", "dataset_mapping_versions",
              "workflows", "workflow_steps", "audit_history"
            )
            val tableCounts = {
              val stmt = conn.createStatement()
              try {
                tables.foldLeft(Json.obj()) { (obj, table) =>
                  val count = Try {
                    val rs = stmt.executeQuery(s"SELECT count(*) FROM $table")
                    try { rs.next(); rs.getLong(1) } finally rs.close()
                  }.getOrElse(-1L)
                  obj + (table -> Json.toJson(count))
                }
              } finally stmt.close()
            }

            // Services initialized?
            val services = Json.obj(
              "DsInfoService" -> GlobalDsInfoService.get().isDefined,
              "OaiSourceRepository" -> GlobalOaiSourceRepository.get().isDefined,
              "MappingMetadataRepository" -> GlobalMappingMetadataRepository.get().isDefined
            )

            Json.obj(
              "configured" -> true,
              "healthy" -> healthy,
              "readEnabled" -> narthexConfig.postgresReadEnabled,
              "runMigration" -> narthexConfig.runPostgresMigration,
              "schema" -> schemaVersion,
              "connectionPool" -> poolStats,
              "tableCounts" -> tableCounts,
              "services" -> services
            )
          } finally conn.close()
        }

        status match {
          case scala.util.Success(json) =>
            Ok(Json.prettyPrint(json)).as(ContentTypes.JSON)
          case scala.util.Failure(ex) =>
            Ok(Json.prettyPrint(Json.obj(
              "configured" -> true,
              "healthy" -> false,
              "readEnabled" -> narthexConfig.postgresReadEnabled,
              "error" -> ex.getMessage
            ))).as(ContentTypes.JSON)
        }
    }
  }

}
