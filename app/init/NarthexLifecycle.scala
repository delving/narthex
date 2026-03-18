package init

import javax.inject._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import play.api.inject.ApplicationLifecycle
import play.api.Logging
import akka.actor.{ActorRef, ActorSystem}
import harvest.PeriodicHarvest
import organization.OrgContext
import mapping.PeriodicSkosifyCheck
import services.{DatabaseService, DsInfoService, FusekiMigration, GlobalDatabaseService, GlobalDsInfoService, GlobalWorkflowDatabase, PostgresDatasetRepository}
import init.NarthexConfig
import scala.util.{Failure, Success}

@Singleton
class NarthexLifecycle @Inject() (
    lifecycle: ApplicationLifecycle,
    actorSystem: ActorSystem,
    orgContext: OrgContext,
    narthexConfig: NarthexConfig
)(implicit ec: ExecutionContext)
    extends Logging {

  logger.info("Narthex starting up...")

  // Initialize PostgreSQL (if configured)
  narthexConfig.postgresConfig.foreach { pgConfig =>
    val dbService = new DatabaseService(pgConfig)
    dbService.initialize()
    GlobalDatabaseService.set(dbService)
    logger.info("PostgreSQL DatabaseService initialized")

    // Create DsInfoService for PostgreSQL-backed reads
    val pgRepo = new PostgresDatasetRepository(dbService)
    GlobalDsInfoService.set(new DsInfoService(pgRepo))
    logger.info(s"DsInfoService initialized (read-enabled: ${narthexConfig.postgresReadEnabled})")

    // Run Fuseki-to-PostgreSQL migration if enabled
    if (narthexConfig.runPostgresMigration) {
      logger.info("Running Fuseki-to-PostgreSQL migration (narthex.postgres.run-migration = true)...")
      implicit val ts: triplestore.TripleStore = orgContext.ts
      val migration = new FusekiMigration(orgContext, pgRepo, dbService)
      migration.run().onComplete {
        case Success(report) =>
          logger.info(s"Fuseki-to-PostgreSQL migration finished:\n${report.summary}")
          if (report.errors.nonEmpty) {
            logger.warn(s"Migration encountered ${report.errors.size} error(s)")
          }
        case Failure(ex) =>
          logger.error(s"Fuseki-to-PostgreSQL migration failed: ${ex.getMessage}", ex)
      }
    }
  }

  // Initialize workflow database
  GlobalWorkflowDatabase.init(narthexConfig)

  // Test that new string metrics library produces same values as previous library
  // on https://github.com/rockymadden/stringmetric
  //val ro = new RatcliffObershelp
  //System.out.println(ro.similarity("aleksander", "alexandre")) // 0.7368421052631579
  //System.out.println(ro.similarity("pennsylvania", "pencilvaneya")) // 0.6666666666666666

  private val periodicHarvest =
    actorSystem.actorOf(PeriodicHarvest.props(orgContext), "PeriodicHarvest")
  private val harvestTicker = actorSystem.scheduler.scheduleWithFixedDelay(
    1.minute,
    1.minute,
    periodicHarvest,
    PeriodicHarvest.ScanForHarvests
  )

  // This doesn't seem to be used
  val periodicSkosifyCheck: ActorRef = actorSystem.actorOf(
    PeriodicSkosifyCheck.props(orgContext),
    "PeriodicSkosifyCheck"
  )

  // Shut-down hook
  lifecycle.addStopHook { () =>
    logger.info("Narthex shutting down, cleaning up active threads...")

    harvestTicker.cancel()
    GlobalDsInfoService.clear()
    GlobalDatabaseService.close()
    GlobalWorkflowDatabase.close()

    Future.successful(())
  }

}
