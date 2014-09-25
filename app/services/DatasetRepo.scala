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
import actors.Saver.{InterruptSaving, SaveRecords}
import actors._
import akka.actor.{PoisonPill, Props}
import org.apache.commons.io.FileUtils.{deleteDirectory, deleteQuietly}
import play.api.Logger
import play.api.Play.current
import play.api.cache.Cache
import play.libs.Akka
import services.DatasetState._
import services.RecordHandling.{Record, TargetConcept}
import services.RepoUtil.tagToDirectory

class DatasetRepo(val orgRepo: Repo, val name: String, val sourceFile: File, val dir: File) extends RecordHandling {
  val root = new NodeRepo(this, dir)
  val dbName = s"narthex_${orgRepo.orgId}___$name"
  lazy val datasetDb = new DatasetDb(orgRepo.repoDb, name)
  lazy val termDb = new TermDb(dbName)
  lazy val recordDb = new RecordDb(this, dbName)

  override def toString = sourceFile.getCanonicalPath

  def mkdirs = {
    dir.mkdirs()
    this
  }

  def actor(props: Props) = {
    Logger.info(s"Creating actor $name")
    Akka.system.actorOf(props, name)
  }

  def startPmhHarvest(url: String, dataset: String, prefix: String) = {

    def startActor() = {
      val harvester = actor(Harvester.props(this))
      val kickoff = HarvestPMH(url, dataset, prefix)
      Logger.info(s"Harvest $kickoff")
      harvester ! kickoff
    }

    datasetDb.getDatasetInfoOption map {
      datasetInfo =>
        val state = (datasetInfo \ "status" \ "state").text
        if (!HARVESTING.matches(state)) {
          datasetDb.setStatus(HARVESTING, percent = 1)
          startActor()
        }
        else {
          Logger.info("Harvest busy already")
        }
    } getOrElse {
      Logger.info("Fresh database")
      datasetDb.createDataset(HARVESTING, percent = 1)
      startActor()
    }
  }

  def startAdLibHarvest(url: String, dataset: String) = {

    def startActor() = {
      val harvester = actor(Harvester.props(this))
      val kickoff = HarvestAdLib(url, dataset)
      Logger.info(s"Harvest $kickoff")
      harvester ! kickoff
    }

    datasetDb.getDatasetInfoOption map {
      datasetInfo =>
        val state = (datasetInfo \ "status" \ "state").text
        if (!HARVESTING.matches(state)) {
          datasetDb.setStatus(HARVESTING, percent = 1)
          startActor()
        }
        else {
          Logger.info("Harvest busy already")
        }
    } getOrElse {
      Logger.info("Fresh database")
      datasetDb.createDataset(HARVESTING, percent = 1)
      startActor()
    }
    datasetDb.setHarvestInfo("adlib", url, dataset, "")
  }

  def startAnalysis() = {
    deleteDirectory(dir)
    datasetDb.setStatus(SPLITTING, percent = 1)
    val analyzer = actor(Analyzer.props(this))
    analyzer ! Analyzer.Analyze(sourceFile)
  }

  def saveRecords() = {
    val delim = recordDb.getDatasetInfo \ "delimit"
    val recordRoot = (delim \ "recordRoot").text
    val uniqueId = (delim \ "uniqueId").text
    val recordCountText = (delim \ "recordCount").text
    val recordCount = if (recordCountText.isEmpty) 0 else recordCountText.toInt
    // set status now so it's done before the actor starts
    datasetDb.setStatus(SAVING, percent = 1)
    val saver = actor(Saver.props(this))
    saver ! SaveRecords(recordRoot, uniqueId, recordCount, name)
  }

  def index = new File(dir, "index.json")

  def nodeRepo(path: String): Option[NodeRepo] = {
    val nodeDir = path.split('/').toList.foldLeft(dir)((file, tag) => new File(file, tagToDirectory(tag)))
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

  def goToState(newState: DatasetState) = {

    val currentState = datasetDb.getDatasetInfoOption match {
      case Some(info) =>
        fromString((info \ "status" \ "state").text).getOrElse(DELETED)
      case _ =>
        DELETED
    }

    Logger.info(s"$name: $currentState --> $newState")

    val selection = Akka.system.actorSelection(s"akka://application/user/$name")

    newState match {

      case DELETED =>
        datasetDb.removeDataset()
        recordDb.dropDb()
        deleteQuietly(sourceFile)
        deleteDirectory(dir)
        true

      case EMPTY =>
        if (currentState == HARVESTING) {
          // what if there is no actor listening?
          Logger.info("Sending InterruptHarvest")
          selection ! InterruptHarvest()
        }
        datasetDb.setStatus(newState)
        recordDb.dropDb()
        deleteQuietly(sourceFile)
        deleteDirectory(dir)
        true

      case READY =>
        if (currentState == SPLITTING || currentState == ANALYZING) {
          // what if there is no actor listening?
          Logger.info("Sending InterruptAnalysis")
          selection ! InterruptAnalysis()
        }
        else {
          datasetDb.setStatus(newState)
          recordDb.dropDb()
          deleteDirectory(dir)
        }
        true

      case ANALYZED =>
        if (currentState == SAVING) {
          // what if there is no actor listening?
          Logger.info("Sending InterruptSaving")
          selection ! InterruptSaving()
        }
        else {
          selection ! PoisonPill
          datasetDb.setStatus(newState)
          recordDb.dropDb()
        }
        true

      case SAVED =>
        if (currentState == PUBLISHED) {
          datasetDb.setStatus(SAVED)
          true
        }
        else {
          false
        }

      case PUBLISHED =>
        if (currentState == SAVED) {
          datasetDb.setStatus(PUBLISHED)
          true
        }
        else {
          false
        }

      case _ =>
        false
    }
  }

  def enrichRecords(storedRecords: String): List[Record] = {
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
