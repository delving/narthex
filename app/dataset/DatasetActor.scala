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

package dataset

import java.io.File

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import analysis.Analyzer
import analysis.Analyzer.{AnalysisComplete, AnalyzeFile}
import dataset.DatasetActor._
import dataset.DatasetOrigin._
import dataset.DatasetState.SOURCED
import harvest.Harvester.{HarvestAdLib, HarvestComplete, HarvestPMH}
import harvest.Harvesting.HarvestType
import harvest.{HarvestRepo, Harvester}
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import play.api.Logger
import record.RecordHandling._
import record.Saver
import record.Saver.{SaveComplete, SaveRecords}
import services.FileHandling._

import scala.language.postfixOps

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object DatasetActor {

  case class StartHarvest(modifiedAfter: Option[DateTime])

  case class StartAnalysis()

  case class StartSaving(modifiedAfter: Option[DateTime], file: Option[File])

  case class InterruptWork()

  case class InterruptChild(actorWaiting: ActorRef)

  def props(datasetRepo: DatasetRepo) = Props(new DatasetActor(datasetRepo))

}

class DatasetActor(val datasetRepo: DatasetRepo) extends Actor {
  val log = Logger
  val db = datasetRepo.datasetDb

  def receive = {

    // === harvest

    case StartHarvest(modifiedAfter) =>
      log.info(s"Start harvest $datasetRepo modified=$modifiedAfter")
      clearDir(datasetRepo.analyzedDir)
      db.infoOption.foreach { info =>
        val harvest = info \ "harvest"
        HarvestType.fromString((harvest \ "harvestType").text).map { harvestType =>
          val harvestRepo = new HarvestRepo(datasetRepo.harvestDir, harvestType)
          val harvester = context.child("harvester").getOrElse(context.actorOf(Harvester.props(datasetRepo, harvestRepo)))
          val url = (harvest \ "url").text
          val database = (harvest \ "dataset").text
          val prefix = (harvest \ "prefix").text
          val kickoff = harvestType match {
            case HarvestType.PMH =>
              HarvestPMH(url, database, prefix, modifiedAfter)
            case HarvestType.ADLIB =>
              HarvestAdLib(url, database, modifiedAfter)
          }
          harvester ! kickoff
        } getOrElse (throw new RuntimeException(s"Unknown harvest type!"))
      }

    case HarvestComplete(modifiedAfter, fileOption, errorOption) =>
      log.info(s"Harvest complete $datasetRepo, error=$errorOption")
      db.endProgress(errorOption)
      if (!errorOption.isDefined) modifiedAfter.map { after =>
        self ! StartSaving(modifiedAfter, fileOption)
      } getOrElse {
        db.setStatus(SOURCED)
        self ! StartAnalysis()
      }
      sender ! PoisonPill

    // === analysis

    case StartAnalysis() =>
      log.info(s"Start analysis $datasetRepo")
      datasetRepo.getLatestIncomingFile.map { file =>
        val analyzer = context.child("analyzer").getOrElse(context.actorOf(Analyzer.props(datasetRepo)))
        analyzer ! AnalyzeFile(file)
      } getOrElse {
        log.warn(s"No latest incoming file for $datasetRepo")
      }

    case AnalysisComplete(errorOption) =>
      log.info(s"Analysis complete $datasetRepo, error=$errorOption")
      db.endProgress(errorOption)
      db.setTree(errorOption.isEmpty)
      if (errorOption.isDefined) FileUtils.deleteQuietly(datasetRepo.analyzedDir)
      sender ! PoisonPill

    // === saving

    case StartSaving(modifiedAfter, fileOption) =>
      log.info(s"Start saving $datasetRepo modified=$modifiedAfter")
      datasetRepo.datasetDb.infoOption.foreach { info =>
        val delimit = info \ "delimit"
        val recordCountText = (delimit \ "recordCount").text
        val recordCount = if (recordCountText.nonEmpty) recordCountText.toInt else 0
        val kickoff = if (HARVEST.matches((info \ "origin" \ "type").text)) {
          val recordRoot = s"/$RECORD_LIST_CONTAINER/$RECORD_CONTAINER"
          Some(SaveRecords(modifiedAfter, fileOption, recordRoot, s"$recordRoot/$RECORD_UNIQUE_ID", recordCount, Some(recordRoot)))
        }
        else {
          val recordRoot = (delimit \ "recordRoot").text
          val uniqueId = (delimit \ "uniqueId").text
          if (recordRoot.trim.nonEmpty)
            Some(SaveRecords(modifiedAfter, fileOption, recordRoot, uniqueId, recordCount, None))
          else
            None
        }
        kickoff.foreach { k =>
          val saver = context.child("saver").getOrElse(context.actorOf(Saver.props(datasetRepo)))
          saver ! k
        }
      }

    case SaveComplete(errorOption) =>
      log.info(s"Save complete $datasetRepo, error=$errorOption")
      db.endProgress(errorOption)
      db.setRecords(errorOption.isEmpty)
      sender ! PoisonPill

    // === stop stuff

    case InterruptChild(actorWaiting) => // return true if an interrupt happened
      val interrupted = context.children.exists { child: ActorRef =>
        child ! InterruptWork()
        true // found one to interrupt
      }
      log.info(s"InterruptChild, report back to $actorWaiting interrupted=$interrupted")
      actorWaiting ! interrupted

    case spurious =>
      log.error(s"Spurious message $datasetRepo: $spurious")
  }
}









