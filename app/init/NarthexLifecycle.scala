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

@Singleton
class NarthexLifecycle @Inject() (
    lifecycle: ApplicationLifecycle,
    actorSystem: ActorSystem,
    orgContext: OrgContext
)(implicit ec: ExecutionContext)
    extends Logging {

  logger.info("Narthex starting up...")

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

    Future.successful(())
  }

}
