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
package services

import java.io.File

import actors.Analyzer.InterruptAnalysis
import actors.Harvester.{HarvestAdLib, HarvestPMH, InterruptHarvest}
import actors.Saver.SaveRecords
import actors._
import akka.actor.{PoisonPill, Props}
import play.api.Logger
import play.api.Play.current
import play.api.cache.Cache
import play.libs.Akka
import services.DatasetOrigin.HARVEST
import services.DatasetState._
import services.FileHandling.clearDir
import services.Harvesting._
import services.RecordHandling.{StoredRecord, TargetConcept}
import services.RepoUtil.pathToDirectory

class DatasetRepo(val orgRepo: Repo, val name: String) extends RecordHandling {

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

  def actor(props: Props) = {
    Logger.info(s"Creating actor $name")
    Akka.system.actorOf(props, name)
  }

  def mkdirs = {
    rootDir.mkdirs()
    incomingDir.mkdir()
    analyzedDir.mkdir()
    harvestDir.mkdir()
    this
  }

  def createIncomingFile(fileName: String) = new File(incomingDir, fileName)

  def getLatestIncomingFile = incomingDir.listFiles().toList.sortBy(_.lastModified()).lastOption

  def startPmhHarvest(url: String, dataset: String, prefix: String) = datasetDb.getDatasetInfoOption map {
    datasetInfo =>
      val state = (datasetInfo \ "status" \ "state").text
      if (!HARVESTING.matches(state)) {
        datasetDb.setStatus(HARVESTING, percent = 1)
        datasetDb.setOrigin(HARVEST, "?")
        datasetDb.setHarvestInfo("pmh", url, dataset, prefix)
        val harvestCron = Harvesting.harvestCron(datasetInfo)
        datasetDb.setHarvestCron(harvestCron)
        datasetDb.setRecordDelimiter(PMH_RECORD_ROOT, PMH_UNIQUE_ID)
        val harvestRepo = new HarvestRepo(harvestDir, PMH_RECORD_ROOT, PMH_UNIQUE_ID, Some(PMH_DEEP_RECORD_CONTAINER))
        val harvester = actor(Harvester.props(this, harvestRepo))
        val kickoff = HarvestPMH(url, dataset, prefix, None)
        Logger.info(s"Harvest $kickoff")
        harvester ! kickoff
      }
      else {
        Logger.info("Harvest busy already")
      }
  }

  def startAdLibHarvest(url: String, dataset: String) = datasetDb.getDatasetInfoOption map {
    datasetInfo =>
      val state = (datasetInfo \ "status" \ "state").text
      if (!HARVESTING.matches(state)) {
        datasetDb.setStatus(HARVESTING, percent = 1)
        datasetDb.setOrigin(HARVEST, "?")
        datasetDb.setHarvestInfo("adlib", url, dataset, "adlib")
        val harvestCron = Harvesting.harvestCron(datasetInfo)
        datasetDb.setHarvestCron(harvestCron)
        datasetDb.setRecordDelimiter(ADLIB_RECORD_ROOT, ADLIB_UNIQUE_ID)
        val harvestRepo = new HarvestRepo(harvestDir, ADLIB_RECORD_ROOT, ADLIB_UNIQUE_ID, deepRecordContainer = None)
        val harvester = actor(Harvester.props(this, harvestRepo))
        val kickoff = HarvestAdLib(url, dataset, modifiedAfter = None)
        Logger.info(s"Harvest $kickoff")
        harvester ! kickoff
      }
      else {
        Logger.info("Harvest busy already")
      }
  }

  def nextHarvest() = datasetDb.getDatasetInfoOption map {
    datasetInfo =>
      val state = (datasetInfo \ "status" \ "state").text
      val harvestCron = Harvesting.harvestCron(datasetInfo)
      if (!HARVESTING.matches(state) && harvestCron.timeToWork) {
        datasetDb.setStatus(HARVESTING, percent = 1)
        val nextHarvestCron = harvestCron.next
        datasetDb.setHarvestCron(if (nextHarvestCron.timeToWork) harvestCron.now else nextHarvestCron)
        val harvest = datasetInfo \ "harvest"
        val harvestType = (harvest \ "type").text
        val (harvestRepo, kickoff) = harvestType match {
          case "pmh" =>
            (new HarvestRepo(harvestDir, PMH_RECORD_ROOT, PMH_UNIQUE_ID, Some(PMH_DEEP_RECORD_CONTAINER)),
              HarvestPMH(
                url = (harvest \ "url").text,
                set = (harvest \ "dataset").text,
                prefix = (harvest \ "prefix").text,
                modifiedAfter = Some(harvestCron.previous)
              ))

          case "adlib" =>
            (new HarvestRepo(harvestDir, ADLIB_RECORD_ROOT, ADLIB_UNIQUE_ID, deepRecordContainer = None),
              HarvestAdLib(
                url = (harvest \ "url").text,
                database = (harvest \ "dataset").text,
                modifiedAfter = Some(harvestCron.previous)
              ))

          case _ =>
            throw new RuntimeException(s"Unknown harvest type: $harvestType")
        }
        val harvester = actor(Harvester.props(this, harvestRepo))
        Logger.info(s"Re-harvest $kickoff")
        harvester ! kickoff
      }
      else {
        Logger.info(s"No re-harvest of $harvestCron")
      }
  }

  def startAnalysis() = {
    getLatestIncomingFile.map { incomingFile =>
      clearDir(analyzedDir)
      datasetDb.setStatus(SPLITTING, percent = 1)
      val analyzer = actor(Analyzer.props(this))
      analyzer ! Analyzer.AnalyzeFile(incomingFile)
    }
  }

  def saveRecords() = {
    val info = recordDb.getDatasetInfo
    val delim = info \ "delimit"
    val recordCountText = (delim \ "recordCount").text
    val recordCount = if (recordCountText.isEmpty) 0 else recordCountText.toInt
    val message = if (HARVEST.matches((info \ "origin" \ "type").text)) {
      val recordRoot = s"/$RECORD_LIST_CONTAINER/$RECORD_CONTAINER"
      SaveRecords(recordRoot, s"$recordRoot/$RECORD_UNIQUE_ID", recordCount, Some(recordRoot))
    }
    else {
      val recordRoot = (delim \ "recordRoot").text
      val uniqueId = (delim \ "uniqueId").text
      SaveRecords(recordRoot, uniqueId, recordCount, None)
    }
    // set status now so it's done before the actor starts
    datasetDb.setStatus(SAVING, percent = 1)
    val saver = actor(Saver.props(this))
    saver ! message
  }

  def index = new File(analyzedDir, "index.json")

  def nodeRepo(path: String): Option[NodeRepo] = {
    val nodeDir = path.split('/').toList.foldLeft(analyzedDir)((file, tag) => new File(file, pathToDirectory(tag)))
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

    val selection = Akka.system.actorSelection(s"akka://application/user/$name")

    if (newState != currentState) newState match {

      case DELETED =>
        datasetDb.removeDataset()
        recordDb.dropDb()
        clearDir(incomingDir)
        clearDir(analyzedDir)
        true

      case EMPTY =>
        if (currentState == HARVESTING) {
          // what if there is no actor listening?
          Logger.info("Sending InterruptHarvest")
          selection ! InterruptHarvest()
        }
        datasetDb.setStatus(newState)
        recordDb.dropDb()
        clearDir(incomingDir)
        clearDir(analyzedDir)
        clearDir(harvestDir)
        true

      case READY =>
        if (currentState == SPLITTING || currentState == ANALYZING) {
          // what if there is no actor listening?
          Logger.info("Sending InterruptAnalysis")
          // todo: what about InterruptCollection
          selection ! InterruptAnalysis()
        }
        else {
          datasetDb.setStatus(newState)
          recordDb.dropDb()
          clearDir(analyzedDir)
        }
        true

      case ANALYZED =>
        if (currentState == SAVING) {
          // todo: PROBLEMS HERE - REVIEW
          // what if there is no actor listening?
          Logger.info("Sending InterruptCollecting")
          selection ! InterruptAnalysis()
        }
        else {
          selection ! PoisonPill
          datasetDb.setStatus(newState)
          recordDb.dropDb()
        }
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
