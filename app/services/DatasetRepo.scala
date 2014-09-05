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

import actors.Harvester.{HarvestAdLib, HarvestPMH}
import actors.{Analyzer, Harvester}
import org.apache.commons.io.FileUtils.{deleteDirectory, deleteQuietly}
import play.api.Logger
import play.libs.Akka
import services.DatasetState.{HARVESTING, SPLITTING}
import services.RepoUtil.tagToDirectory

class DatasetRepo(val orgRepo: Repo, val name: String, val sourceFile: File, val dir: File) {
  val root = new NodeRepo(this, dir)
  val dbName = s"narthex_${orgRepo.orgId}___$name"
  lazy val datasetDb = new DatasetDb(orgRepo.repoDb, name)
  lazy val termRepo = new TermDb(dbName)
  lazy val recordRepo = new RecordDb(this, dbName)

  override def toString = sourceFile.getCanonicalPath

  def mkdirs = {
    dir.mkdirs()
    this
  }

  def startPmhHarvest(url: String, dataset: String, prefix: String) = {

    def startActor() = {
      val harvester = Akka.system.actorOf(Harvester.props(this), s"pmh-${sourceFile.getName}")
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
      val harvester = Akka.system.actorOf(Harvester.props(this), s"adlib-${sourceFile.getName}")
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
  }

  def startAnalysis() = {
    deleteDirectory(dir)
    datasetDb.setStatus(SPLITTING, percent = 1)
    val analyzer = Akka.system.actorOf(Analyzer.props(this), s"analyze-${sourceFile.getName}")
    analyzer ! Analyzer.Analyze(sourceFile)
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

  def delete() = {
    datasetDb.setStatus(DatasetState.DELETED)
    recordRepo.dropDb()
    deleteQuietly(sourceFile)
    deleteDirectory(dir)
  }
}
