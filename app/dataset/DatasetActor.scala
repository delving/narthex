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
import harvest.Harvesting.HarvestType.{ADLIB, PMH}
import harvest.{HarvestRepo, Harvester}
import mapping.CategoryCounter
import mapping.CategoryCounter.{CategoryCountComplete, CountCategories}
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import play.api.Logger
import record.PocketParser._
import record.Saver
import record.Saver._
import services.FileHandling._
import services.SipFile.SipMapper
import services.SipRepo._

import scala.language.postfixOps

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object DatasetActor {

  case class StartHarvest(modifiedAfter: Option[DateTime], justDate: Boolean)

  case class StartAnalysis()

  case class StartSaving(modifiedAfter: Option[DateTime], file: File, sipMapperOpt: Option[SipMapper])

  case class StartCategoryCounting()

  case class InterruptWork()

  case class InterruptChild(actorWaiting: ActorRef)

  def props(datasetRepo: DatasetRepo) = Props(new DatasetActor(datasetRepo))

}

class DatasetActor(val datasetRepo: DatasetRepo) extends Actor {

  // todo: supervisor strategy!

  val log = Logger
  val db = datasetRepo.datasetDb

  def receive = {

    // === harvest

    case StartHarvest(modifiedAfter, justDate) =>
      log.info(s"Start harvest $datasetRepo modified=$modifiedAfter")
      clearDir(datasetRepo.analyzedDir)
      db.infoOpt.foreach { info =>
        HarvestType.harvestTypeFromInfo(info).map { harvestType =>
          val harvestRepo = new HarvestRepo(datasetRepo.harvestDir, harvestType)
          val harvester = context.child("harvester").getOrElse(context.actorOf(Harvester.props(datasetRepo, harvestRepo)))
          val harvest = info \ "harvest"
          val url = (harvest \ "url").text
          val database = (harvest \ "dataset").text
          val prefix = (harvest \ "prefix").text
          val kickoff = harvestType match {
            case PMH =>
              HarvestPMH(url, database, prefix, modifiedAfter, justDate)
            case ADLIB =>
              HarvestAdLib(url, database, modifiedAfter)
          }
          harvester ! kickoff
        } getOrElse (throw new RuntimeException(s"Unknown harvest type!"))
      }

    case HarvestComplete(modifiedAfter, fileOption, errorOption) =>
      log.info(s"Harvest complete $datasetRepo, error=$errorOption, file=$fileOption")
      db.endProgress(errorOption)
      if (errorOption.isEmpty) db.infoOpt.foreach { info =>
        fileOption.map { file =>
          if (modifiedAfter.isEmpty) db.setStatus(SOURCED) // first harvest
          clearDir(datasetRepo.analyzedDir)
          datasetRepo.datasetDb.setTree(ready = false)
          val sipMapperOpt = datasetRepo.sipRepo.latestSipFile.flatMap(_.createSipMapper)
          self ! StartSaving(modifiedAfter, file, sipMapperOpt)

          // todo: somehow send the saver a message to have it GenerateSourceFromHaervest


        } getOrElse {
          log.info(s"No file to save for $datasetRepo")
        }
      }
      sender ! PoisonPill

    // === analysis

    case StartAnalysis() =>
      log.info(s"Start analysis $datasetRepo")
      datasetRepo.getLatestIncomingFile.map { file =>
        val analyzer = context.child("analyzer").getOrElse(context.actorOf(Analyzer.props(datasetRepo)))
        analyzer ! AnalyzeFile(file)
      } getOrElse {
        log.warn(s"No file to analyze for $datasetRepo")
      }

    case AnalysisComplete(errorOption) =>
      log.info(s"Analysis complete $datasetRepo, error=$errorOption")
      db.endProgress(errorOption)
      db.setTree(errorOption.isEmpty)
      if (errorOption.isDefined) FileUtils.deleteQuietly(datasetRepo.analyzedDir)
      sender ! PoisonPill

    // == categorization

    case StartCategoryCounting() =>
      log.info(s"Start categorizing $datasetRepo")
      datasetRepo.getLatestIncomingFile.map { file =>
        datasetRepo.datasetDb.infoOpt.foreach { info =>
          val delimit = info \ "delimit"
          val recordCountText = (delimit \ "recordCount").text
          val recordCount = if (recordCountText.nonEmpty) recordCountText.toInt else 0
          val kickoffOption = if (HARVEST.matches((info \ "origin" \ "type").text)) {
            Some(CountCategories(file, POCKET_RECORD_ROOT,POCKET_UNIQUE_ID, POCKET_DEEP_RECORD_ROOT))
          }
          else {
            val recordRoot = (delimit \ "recordRoot").text
            val uniqueId = (delimit \ "uniqueId").text
            if (recordRoot.trim.nonEmpty)
              Some(CountCategories(file, recordRoot, uniqueId, None))
            else
              None
          }
          kickoffOption.foreach { kickoff =>
            val categoryCounter = context.child("category-counter").getOrElse(context.actorOf(CategoryCounter.props(datasetRepo)))
            categoryCounter ! kickoff
          }
        }
      } getOrElse {
        log.warn(s"No latest incoming file for $datasetRepo")
      }

    case CategoryCountComplete(dataset, categoryCounts, errorOption) =>
      log.info(s"Category Counts arrived: $categoryCounts")
      db.endProgress(errorOption)
      context.parent ! CategoryCountComplete(datasetRepo.datasetName, categoryCounts, errorOption)
      sender ! PoisonPill

    // === saving

    case StartSaving(modifiedAfter, file, sipMapperOpt) =>
      log.info(s"Start saving $datasetRepo from $file modified=$modifiedAfter")
      datasetRepo.datasetDb.infoOpt.foreach { info =>
        val delimit = info \ "delimit"
        val recordCountText = (delimit \ "recordCount").text
        val recordCount = if (recordCountText.nonEmpty) recordCountText.toInt else 0
        val kickoffOption: Option[SaveRecords] = originFromInfo(info).flatMap {
          case DROP =>
            val recordRoot = (delimit \ "recordRoot").text
            val uniqueId = (delimit \ "uniqueId").text
            if (recordRoot.trim.nonEmpty)
              Some(SaveRecords(modifiedAfter, file, recordRoot, uniqueId, recordCount, None, sipMapperOpt))
            else
              None
          case HARVEST =>
            val ht = HarvestType.harvestTypeFromInfo(info).getOrElse(throw new RuntimeException(s"Harvest origin but no harvest type!"))
            Some(SaveRecords(modifiedAfter, file, ht.recordRoot, ht.uniqueId, recordCount, ht.deepRecordContainer, sipMapperOpt))
          case SIP_SOURCE =>
            datasetRepo.sipRepo.latestSipFile.flatMap { sipFile =>
              sipFile.copySourceToTempFile.map { sourceXmlGz =>
                SaveRecords(modifiedAfter, sourceXmlGz, SIP_SOURCE_RECORD_ROOT, SIP_SOURCE_UNIQUE_ID, recordCount, Some(SIP_SOURCE_RECORD_ROOT), sipMapperOpt)
              }
            }
          case SIP_HARVEST =>
            val ht = HarvestType.harvestTypeFromInfo(info).getOrElse(throw new RuntimeException(s"Harvest origin but no harvest type!"))
            Some(SaveRecords(modifiedAfter, file, ht.recordRoot, ht.uniqueId, recordCount, ht.deepRecordContainer, sipMapperOpt))
        }
        kickoffOption.foreach { kickoff =>
          val saver = context.child("saver").getOrElse(context.actorOf(Saver.props(datasetRepo)))
          saver ! kickoff
        }
      }

    case SaveComplete(errorOption) =>
      log.info(s"Save complete $datasetRepo, error=$errorOption")
      db.endProgress(errorOption)
      db.setRecords(errorOption.isEmpty)
      sender ! PoisonPill

    // === generating

    case GenerateSourceFromSipFile() =>
      val saver = context.child("saver").getOrElse(context.actorOf(Saver.props(datasetRepo)))
      saver ! GenerateSourceFromSipFile()

    case GenerateSourceFromHarvest() =>
      val saver = context.child("saver").getOrElse(context.actorOf(Saver.props(datasetRepo)))
      saver ! GenerateSourceFromHarvest()

    case GenerationComplete(file, recordCount) =>
      // todo: figure out if there are other things to trigger
      log.info(s"Generated file $file with $recordCount records")
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









