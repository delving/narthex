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
import dataset.SipFile.SipMapper
import harvest.Harvester
import harvest.Harvester.{HarvestAdLib, HarvestComplete, HarvestPMH}
import harvest.Harvesting.HarvestType.{ADLIB, PMH, harvestTypeFromInfo}
import mapping.CategoryCounter
import mapping.CategoryCounter.{CategoryCountComplete, CountCategories}
import org.joda.time.DateTime
import play.api.Logger
import record.PocketParser._
import record.Saver
import record.Saver._

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
      datasetRepo.clearTreeDir()
      db.infoOpt.foreach { info =>
        harvestTypeFromInfo(info).map { harvestType =>
          val harvest = info \ "harvest"
          val url = (harvest \ "url").text
          val database = (harvest \ "dataset").text
          val prefix = (harvest \ "prefix").text
          val kickoff = harvestType match {
            case PMH => HarvestPMH(url, database, prefix, modifiedAfter, justDate)
            case ADLIB => HarvestAdLib(url, database, modifiedAfter)
          }
          val harvester = context.child("harvester").getOrElse(context.actorOf(Harvester.props(datasetRepo)))
          harvester ! kickoff
        } getOrElse {
          self ! HarvestComplete(modifiedAfter, None, Some(s"Harvest type not recognized"))
        }
      }

    case HarvestComplete(modifiedAfter, fileOption, errorOption) =>
      log.info(s"Harvest complete $datasetRepo, error=$errorOption, file=$fileOption")
      db.endProgress(errorOption)
      if (errorOption.isEmpty) db.infoOpt.foreach { info =>
        fileOption.map { file =>
          if (modifiedAfter.isEmpty) db.setStatus(SOURCED) // first harvest
          datasetRepo.clearTreeDir()
          datasetRepo.datasetDb.setTree(ready = false)
          val sipMapperOpt = datasetRepo.sipRepo.latestSipFile.flatMap(_.createSipMapper)
          self ! StartSaving(modifiedAfter, file, sipMapperOpt)

          // todo: somehow send the saver a message to have it GenerateSourceFromHarvest


        } getOrElse {
          log.info(s"No file to save for $datasetRepo")
        }
      }
      if (sender != self) sender ! PoisonPill

    // === analysis

    case StartAnalysis() =>
      log.info(s"Start analysis $datasetRepo")
      if (datasetRepo.sourceFile.exists()) {
        val analyzer = context.child("analyzer").getOrElse(context.actorOf(Analyzer.props(datasetRepo)))
        analyzer ! AnalyzeFile(datasetRepo.sourceFile)
      } else datasetRepo.rawFile.map { rawFile =>
        val analyzer = context.child("analyzer").getOrElse(context.actorOf(Analyzer.props(datasetRepo)))
        analyzer ! AnalyzeFile(rawFile)
      } getOrElse {
        self ! AnalysisComplete(Some(s"No file to analyze for $datasetRepo"))
      }

    case AnalysisComplete(errorOption) =>
      log.info(s"Analysis complete $datasetRepo, error=$errorOption")
      db.endProgress(errorOption)
      db.setTree(errorOption.isEmpty)
      if (errorOption.isDefined) datasetRepo.clearTreeDir()
      if (sender != self) sender ! PoisonPill

    // == categorization

    case StartCategoryCounting() =>
      log.info(s"Start categorizing $datasetRepo")
      if (datasetRepo.sourceFile.exists()) {
        datasetRepo.datasetDb.infoOpt.foreach { info =>
          val delimit = info \ "delimit"
          val recordCountText = (delimit \ "recordCount").text
          val recordCount = if (recordCountText.nonEmpty) recordCountText.toInt else 0
          val kickoffOption = if (HARVEST.matches((info \ "origin" \ "type").text)) {
            Some(CountCategories(datasetRepo.sourceFile, POCKET_RECORD_ROOT, POCKET_UNIQUE_ID, POCKET_DEEP_RECORD_ROOT))
          }
          else {
            val recordRoot = (delimit \ "recordRoot").text
            val uniqueId = (delimit \ "uniqueId").text
            if (recordRoot.trim.nonEmpty)
              Some(CountCategories(datasetRepo.sourceFile, recordRoot, uniqueId, None))
            else
              None
          }
          kickoffOption.foreach { kickoff =>
            val categoryCounter = context.child("category-counter").getOrElse(context.actorOf(CategoryCounter.props(datasetRepo)))
            categoryCounter ! kickoff
          }
        }
      }
      else {
        self ! CategoryCountComplete(datasetRepo.datasetName, List.empty, Some(s"No source file for categorizing $datasetRepo"))
      }

    case CategoryCountComplete(dataset, categoryCounts, errorOption) =>
      log.info(s"Category Counts arrived: $categoryCounts")
      db.endProgress(errorOption)
      context.parent ! CategoryCountComplete(datasetRepo.datasetName, categoryCounts, errorOption)
      if (sender != self) sender ! PoisonPill

    // === saving

    case StartSaving(modifiedAfter, file, sipMapperOpt) =>
      log.info(s"Start saving $datasetRepo from $file modified=$modifiedAfter")
      datasetRepo.datasetDb.infoOpt.foreach { info =>
        val delimit = info \ "delimit"
        val recordCountText = (delimit \ "recordCount").text
        val recordCount = if (recordCountText.nonEmpty) recordCountText.toInt else 0
        val stagingFacts = datasetRepo.stagingRepo.stagingFacts
        val kickoffOption: Option[SaveRecords] = originFromInfo(info).flatMap {
          case DROP =>
            val recordRoot = (delimit \ "recordRoot").text
            val uniqueId = (delimit \ "uniqueId").text
            if (recordRoot.trim.nonEmpty)
              Some(SaveRecords(modifiedAfter, file, stagingFacts, sipMapperOpt))
            else
              None
          case HARVEST =>
            val ht = harvestTypeFromInfo(info).getOrElse(throw new RuntimeException(s"Harvest origin but no harvest type!"))
            Some(SaveRecords(modifiedAfter, file, stagingFacts, sipMapperOpt))
          case SIP_SOURCE =>
            datasetRepo.sipRepo.latestSipFile.flatMap { sipFile =>
              sipFile.copySourceToTempFile.map { sourceXmlGz =>
                SaveRecords(modifiedAfter, sourceXmlGz, stagingFacts, sipMapperOpt)
              }
            }
          case SIP_HARVEST =>
            val ht = harvestTypeFromInfo(info).getOrElse(throw new RuntimeException(s"Harvest origin but no harvest type!"))
            Some(SaveRecords(modifiedAfter, file, stagingFacts, sipMapperOpt))
        }
        kickoffOption.map { kickoff =>
          val saver = context.child("saver").getOrElse(context.actorOf(Saver.props(datasetRepo)))
          saver ! kickoff
        } getOrElse {
          self ! SaveComplete(Some(s"Cannot save records $datasetRepo"))
        }
      }

    case SaveComplete(errorOption) =>
      log.info(s"Save complete $datasetRepo, error=$errorOption")
      db.endProgress(errorOption)
      db.setRecords(errorOption.isEmpty)
      if (sender != self) sender ! PoisonPill

    // === generating

    case GenerateSource() =>
      val saver = context.child("saver").getOrElse(context.actorOf(Saver.props(datasetRepo)))
      saver ! GenerateSource()

    case SourceGenerationComplete(file, recordCount) =>
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









