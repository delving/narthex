//===========================================================================
//    Copyright 2014 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//===========================================================================

package harvest

import scala.util.{Failure, Success}
import scala.language.postfixOps
import play.api.Logger
import akka.actor.{Actor, Props}

import dataset.DatasetActor.{FromScratch, FromScratchIncremental, ModifiedAfter, StartHarvest}
import dataset.DsInfo
import dataset.DsInfo.{DsState, withDsInfo}
import harvest.PeriodicHarvest.ScanForHarvests
import organization.OrgContext
import services.Temporal.DelayUnit
import triplestore.TripleStore

object PeriodicHarvest {

  case object ScanForHarvests

  def props(orgContext: OrgContext) = Props(new PeriodicHarvest(orgContext))

  val harvestingAllowed = List(DsState.SAVED, DsState.INCREMENTAL_SAVED)
}

class PeriodicHarvest(orgContext: OrgContext) extends Actor {

  private val logger = Logger(getClass)

  import context.dispatcher
  implicit val ts: TripleStore = orgContext.ts


  def receive = {

    case ScanForHarvests =>
      // OPTIMIZATION: Only query datasets with harvestable states to prevent error dataset timestamp updates
      val allowedStateStrings = PeriodicHarvest.harvestingAllowed.map(_.toString)
      logger.info(s"PeriodicHarvest: Scanning for datasets in states: ${allowedStateStrings.mkString(", ")}")
      val futureList = DsInfo.listDsInfoWithStateFilter(orgContext, allowedStateStrings)
      futureList.onComplete {
        case Success(list) =>
          logger.info(s"PeriodicHarvest: Found ${list.length} datasets in harvestable states: ${list.map(_.spec).mkString(", ")}")

          val datasetsWithPreviousTime = list.filter(info => info.hasPreviousTime())
          logger.info(s"PeriodicHarvest: ${datasetsWithPreviousTime.length} datasets have previous harvest time: ${datasetsWithPreviousTime.map(_.spec).mkString(", ")}")

          datasetsWithPreviousTime.
            sortWith((s, t) => s.getPreviousHarvestTime().isBefore(t.getPreviousHarvestTime())).
            foreach { listedInfo =>
              val harvestCron = listedInfo.currentHarvestCron

              // Extra debugging for specific dataset
              if (listedInfo.spec == "bevrijdingsmuseum-zeeland") {
                logger.info(s"DEBUG bevrijdingsmuseum-zeeland: harvestCron=$harvestCron, timeToWork=${harvestCron.timeToWork}, previous=${harvestCron.previous}, delay=${harvestCron.delay}, unit=${harvestCron.unit}, incremental=${harvestCron.incremental}")
              }

              logger.info(s"scheduled ds: ${listedInfo.spec} ${listedInfo.currentHarvestCron.previous.toString()} (time to work: ${harvestCron.timeToWork})")
              if (harvestCron.timeToWork) withDsInfo(listedInfo.spec, orgContext) { info => // the cached version
                if (orgContext.semaphore.tryAcquire(info.spec)) {
                  logger.info(s"Time to work on $info: $harvestCron")
                  val proposedNext = harvestCron.next
                  val next = if (proposedNext.timeToWork) {
                    val revised = harvestCron.now
                    logger.info(s"$info next harvest $proposedNext is already due so adjusting to 'now': $revised")
                    revised
                  }
                  else {
                    logger.info(s"$info next harvest : $proposedNext")
                    proposedNext
                  }
                  logger.info(s"Set harvest cron: $next")
                  info.setHarvestCron(next)
                  val justDate = harvestCron.unit == DelayUnit.WEEKS
                  val strategy = if (harvestCron.incremental) ModifiedAfter(harvestCron.previous, justDate) else FromScratchIncremental
                  val startHarvest = StartHarvest(strategy)

                  logger.info(s"$info acquired semaphore. permits available ${orgContext.semaphore.availablePermits()}")
                  logger.info(s"$info incremental harvest kickoff $startHarvest")
                  orgContext.orgActor ! info.createMessage(startHarvest)
                } else {
                  val sem = orgContext.semaphore
                  logger.info(
                    s"$info skipping, no semaphore available: ${sem.availablePermits()} of ${sem.size()}; ${sem.activeSpecs().toString()}")
                }
            }
          }

          // Check for datasets in retry mode
          checkRetryHarvests()

        case Failure(_) => ()
      }
  }

  /**
   * Check for datasets in retry mode and trigger retry if interval has passed.
   */
  private def checkRetryHarvests(): Unit = {
    val retryIntervalMinutes = orgContext.appConfig.harvestRetryIntervalMinutes

    val futureRetryList = DsInfo.listDsInfoInRetry(orgContext)
    futureRetryList.onComplete {
      case Success(retryList) =>
        logger.info(s"PeriodicHarvest: Found ${retryList.length} datasets in retry mode")

        retryList.filter(_.isTimeForRetry(retryIntervalMinutes)).foreach { info =>
          logger.info(s"PeriodicHarvest: Time for retry harvest of ${info.spec} (attempt #${info.getRetryCount + 1})")

          if (orgContext.semaphore.tryAcquire(info.spec)) {
            logger.info(s"PeriodicHarvest: Acquired semaphore, triggering retry for ${info.spec}")

            // Determine harvest strategy (use FromScratch for retries)
            val strategy = FromScratch

            // Send message to trigger harvest
            orgContext.orgActor ! info.createMessage(StartHarvest(strategy))
          } else {
            logger.info(s"PeriodicHarvest: Could not acquire semaphore for ${info.spec}, will retry later")
          }
        }
      case Failure(e) =>
        logger.error(s"Failed to check retry datasets: ${e.getMessage}", e)
    }
  }
}





