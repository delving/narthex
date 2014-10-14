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

package actors

import actors.Harvester._
import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.pipe
import play.api.Logger
import services.DatasetState._
import services.Harvesting._
import services._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

object Harvester {

  case class HarvestAdLib(url: String, database: String)

  case class HarvestPMH(url: String, set: String, prefix: String)

  case class HarvestProgress(percent: Int)

  case class HarvestComplete(error: Option[String])

  case class InterruptHarvest()

  case class CollectSource()

  case class CollectProgress(percent: Int)

  def props(datasetRepo: DatasetRepo, harvestRepo: HarvestRepo) = Props(new Harvester(datasetRepo, harvestRepo))

  global.getClass // to avoid optimizing the import away
}

class Harvester(val datasetRepo: DatasetRepo, harvestRepo: HarvestRepo) extends Actor with RecordHandling with Harvesting {
  val log = Logger
  var bomb: Option[ActorRef] = None

  def receive = {

    case InterruptHarvest() =>
      log.info(s"Interrupt harvesting $datasetRepo")
      bomb = Some(sender)

    case HarvestAdLib(url, database) =>
      log.info(s"Harvesting $url $database to $datasetRepo")
      val futurePage = fetchAdLibPage(url, database)
      futurePage.onFailure {
        case e => self ! HarvestComplete(Some(e.toString))
      }
      futurePage pipeTo self

    case AdLibHarvestPage(records, url, database, diagnostic) =>
      if (bomb.isDefined) {
        self ! HarvestComplete(Some("Interrupted while harvesting"))
      }
      else {
        val pageName = harvestRepo.acceptPage(records)
        log.info(s"Harvest Page: $pageName - $url $database to $datasetRepo: $diagnostic")
        if (diagnostic.isLast) {
          self ! HarvestComplete(None)
        }
        else {
          datasetRepo.datasetDb.setStatus(HARVESTING, percent = diagnostic.percentComplete)
          val futurePage = fetchAdLibPage(url, database, Some(diagnostic))
          futurePage.onFailure {
            case e => self ! HarvestComplete(Some(e.toString))
          }
          futurePage pipeTo self
        }
      }

    case HarvestPMH(url, set, prefix) =>
      log.info(s"Harvesting $url $set $prefix to $datasetRepo")
      val futurePage = fetchPMHPage(url, set, prefix)
      futurePage.onFailure {
        case e => self ! HarvestComplete(Some(e.toString))
      }
      futurePage pipeTo self

    case PMHHarvestPage(records, url, set, prefix, total, error, resumptionToken) =>
      if (bomb.isDefined) {
        self ! HarvestComplete(Some("Interrupted while harvesting"))
      }
      else {
        error match {
          case Some(errorString) =>
            self ! HarvestComplete(Some(errorString))

          case None =>
            val pageNumber: Option[Int] = harvestRepo.acceptPage(records)
            log.info(s"Harvest Page $pageNumber to $datasetRepo: $resumptionToken")
            resumptionToken match {
              case None =>
                self ! HarvestComplete(None)
              case Some(token) =>
                datasetRepo.datasetDb.setStatus(HARVESTING, percent = token.percentComplete)
                val futurePage = fetchPMHPage(url, set, prefix, Some(token))
                futurePage.onFailure {
                  case e => self ! HarvestComplete(Some(e.toString))
                }
                futurePage pipeTo self
            }
        }
      }

    case HarvestComplete(error) =>
      log.info(s"Harvest complete $datasetRepo error: $error")
      error match {
        case Some(errorString) =>
          datasetRepo.datasetDb.setStatus(EMPTY, error = errorString)
          context.stop(self)
        case None =>
          self ! CollectSource()
      }

    case CollectSource() =>
      val progress = context.actorOf(Props(new Actor() {
        override def receive: Receive = {
          case CollectProgress(percent) =>
            datasetRepo.datasetDb.setStatus(COLLECTING, percent = percent)
        }
      }))
      def sendProgress(percent: Int): Boolean = {
        if (bomb.isDefined) return false
        progress ! CollectProgress(percent)
        true
      }
      val incomingFile = datasetRepo.createIncomingFile(s"$datasetRepo-${System.currentTimeMillis()}.xml")
      val sourceFile = harvestRepo.generateSourceFile(incomingFile, sendProgress, datasetRepo.datasetDb.setNamespaceMap)
      datasetRepo.datasetDb.setStatus(READY)
      context.stop(self)
      context.stop(progress)


  }
}








