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

import analysis.{Analyzer, NodeRepo}
import dataset.DatasetOrigin.HARVEST
import dataset.DatasetState.{fromString, _}
import harvest.Harvester.{HarvestAdLib, HarvestPMH}
import harvest.Harvesting.HarvestType._
import harvest.Harvesting._
import harvest.{HarvestRepo, Harvester, Harvesting}
import org.{OrgActor, OrgRepo}
import play.api.Logger
import play.api.Play.current
import play.api.cache.Cache
import record.RecordHandling.{StoredRecord, TargetConcept}
import record.Saver.SaveRecords
import record.{RecordDb, RecordHandling, Saver}
import services.FileHandling.clearDir
import services._

class DatasetRepo(val orgRepo: OrgRepo, val name: String) extends RecordHandling {

  val rootDir = new File(orgRepo.datasetsDir, name)
  val incomingDir = new File(rootDir, "incoming")
  val analyzedDir = new File(rootDir, "analyzed")
  val harvestDir = new File(rootDir, "harvest")

  val rootNode = new NodeRepo(this, analyzedDir)

  val dbName = s"narthex_${orgRepo.orgId}___$name"
  lazy val datasetDb = new DatasetDb(orgRepo.repoDb, name)
  lazy val termDb = new TermDb(dbName)
  lazy val recordDb = new RecordDb(this, dbName)

  override def toString = name

  def mkdirs = {
    rootDir.mkdirs()
    incomingDir.mkdir()
    analyzedDir.mkdir()
    harvestDir.mkdir()
    this
  }

  def createIncomingFile(fileName: String) = new File(incomingDir, fileName)

  def getLatestIncomingFile = incomingDir.listFiles().toList.sortBy(_.lastModified()).lastOption

  def startHarvest(harvestType: HarvestType, url: String, dataset: String, prefix: String) = datasetDb.getDatasetInfoOption map {
    datasetInfo =>
      DatasetState.fromDatasetInfo(datasetInfo).foreach { state =>
        if (state == EMPTY) {
          datasetDb.setStatus(HARVESTING)
          datasetDb.setOrigin(HARVEST)
          datasetDb.setHarvestInfo(harvestType.name, url, dataset, prefix)
          val harvestCron = Harvesting.harvestCron(datasetInfo)
          datasetDb.setHarvestCron(harvestCron)
          datasetDb.setRecordDelimiter(harvestType.recordRoot, harvestType.uniqueId)
          val harvestRepo = new HarvestRepo(harvestDir, harvestType)
          harvestRepo.clear()
          val kickoff = harvestType match {
            case PMH =>
              HarvestPMH(
                url = url,
                set = dataset,
                prefix = prefix,
                modifiedAfter = None
              )
            case ADLIB =>
              HarvestAdLib(
                url = url,
                database = dataset,
                modifiedAfter = None
              )
          }
          Logger.info(s"Harvest $kickoff")
          OrgActor.create(Harvester.props(this, harvestRepo), name, harvester => harvester ! kickoff)
        }
        else {
          Logger.warn(s"Harvest can only be started in $EMPTY, not $state")
        }
      }
  }

  def nextHarvest() = datasetDb.getDatasetInfoOption map {
    datasetInfo =>
      DatasetState.fromDatasetInfo(datasetInfo).foreach { state =>
        if (state == SAVED) {
          val harvestCron = Harvesting.harvestCron(datasetInfo)
          if (harvestCron.timeToWork) {
            datasetDb.setStatus(HARVESTING)
            val nextHarvestCron = harvestCron.next
            datasetDb.setHarvestCron(if (nextHarvestCron.timeToWork) harvestCron.now else nextHarvestCron)
            val harvest = datasetInfo \ "harvest"
            HarvestType.fromString((harvest \ "harvestType").text).map {
              harvestType =>
                val harvestRepo = new HarvestRepo(harvestDir, harvestType)
                val kickoff = harvestType match {
                  case PMH =>
                    HarvestPMH(
                      url = (harvest \ "url").text,
                      set = (harvest \ "dataset").text,
                      prefix = (harvest \ "prefix").text,
                      modifiedAfter = Some(harvestCron.previous)
                    )
                  case ADLIB =>
                    HarvestAdLib(
                      url = (harvest \ "url").text,
                      database = (harvest \ "dataset").text,
                      modifiedAfter = Some(harvestCron.previous)
                    )
                }
                Logger.info(s"Re-harvest $kickoff")
                OrgActor.create(Harvester.props(this, harvestRepo), name, harvester => harvester ! kickoff)
            } getOrElse {
              Logger.warn(s"No re-harvest of $harvestCron because harvest type was not recognized $harvest")
            }
          }
          else {
            Logger.info(s"No re-harvest of $harvestCron")
          }
        }
        else {
          Logger.warn(s"Incremental harvest can only be started in $SAVED, not $state")
        }
      }
  }

  def startAnalysis() = {
    getLatestIncomingFile.map { incomingFile =>
      clearDir(analyzedDir)
      datasetDb.setStatus(SPLITTING)
      OrgActor.create(Analyzer.props(this), name, analyzer => analyzer ! Analyzer.AnalyzeFile(incomingFile))
    }
  }

  def saveRecords() = {
    val info = recordDb.getDatasetInfo
    val delimit = info \ "delimit"
    val recordCountText = (delimit \ "recordCount").text
    val recordCount = if (recordCountText.isEmpty) 0 else recordCountText.toInt
    val message = if (HARVEST.matches((info \ "origin" \ "type").text)) {
      val recordRoot = s"/$RECORD_LIST_CONTAINER/$RECORD_CONTAINER"
      SaveRecords(recordRoot, s"$recordRoot/$RECORD_UNIQUE_ID", recordCount, Some(recordRoot))
    }
    else {
      val recordRoot = (delimit \ "recordRoot").text
      val uniqueId = (delimit \ "uniqueId").text
      SaveRecords(recordRoot, uniqueId, recordCount, None)
    }
    // set status now so it's done before the actor starts
    datasetDb.setStatus(SAVING)
    OrgActor.create(Saver.props(this), name, saver => saver ! message)
  }

  def index = new File(analyzedDir, "index.json")

  def nodeRepo(path: String): Option[NodeRepo] = {
    val nodeDir = path.split('/').toList.foldLeft(analyzedDir)((file, tag) => new File(file, OrgRepo.pathToDirectory(tag)))
    if (nodeDir.exists()) Some(new NodeRepo(this, nodeDir)) else None
  }

  def status(path: String): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(nodeDirectory) => Some(nodeDirectory.status)
    }
  }

  def sample(path: String, size: Int): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(nodeRepo) =>
        val fileList = nodeRepo.sampleJson.filter(pair => pair._1 == size)
        if (fileList.isEmpty) None else Some(fileList.head._2)
    }
  }

  def histogram(path: String, size: Int): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(nodeRepo) =>
        val fileList = nodeRepo.histogramJson.filter(pair => pair._1 == size)
        if (fileList.isEmpty) None else Some(fileList.head._2)
    }
  }

  def indexText(path: String): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(nodeRepo) => Some(nodeRepo.indexText)
    }
  }

  def uniqueText(path: String): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(nodeRepo) => Some(nodeRepo.uniqueText)
    }
  }

  def histogramText(path: String): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(nodeRepo) => Some(nodeRepo.histogramText)
    }
  }

  def goToState(newState: DatasetState): Boolean = {

    val currentState = datasetDb.getDatasetInfoOption match {
      case Some(info) =>
        fromString((info \ "status" \ "state").text).getOrElse(DELETED)
      case _ =>
        DELETED
    }

    Logger.info(s"$name: $currentState --> $newState")

    if (newState != currentState) newState match {

      case DELETED =>
        datasetDb.removeDataset()
        recordDb.dropDb()
        clearDir(incomingDir)
        clearDir(analyzedDir)
        true

      case EMPTY =>
        OrgActor.shutdownOr(name, {
          datasetDb.setStatus(newState)
          recordDb.dropDb()
          clearDir(incomingDir)
          clearDir(analyzedDir)
          clearDir(harvestDir)
        })
        true

      case READY =>
        OrgActor.shutdownOr(name, {
          datasetDb.setStatus(newState)
          recordDb.dropDb()
          clearDir(analyzedDir)
        })
        true

      case ANALYZED =>
        OrgActor.shutdownOr(name, {
          datasetDb.setStatus(newState)
          recordDb.dropDb()
        })
        true

      case SAVED =>
        false

      case _ =>
        false
    }
    else {
      Logger.info("same state, do nothing")
      false
    }
  }

  def enrichRecords(storedRecords: String): List[StoredRecord] = {
    val pathPrefix = s"${NarthexConfig.ORG_ID}/$name"
    val mappings = Cache.getAs[Map[String, TargetConcept]](name).getOrElse {
      val freshMap = termDb.getMappings.map(m => (m.source, TargetConcept(m.target, m.vocabulary, m.prefLabel))).toMap
      Cache.set(name, freshMap, 60 * 5)
      freshMap
    }
    val parser = new StoredRecordEnricher(pathPrefix, mappings)
    parser.parse(storedRecords)
  }

  def invalidateEnrichementCache() = Cache.remove(name)
}
