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

package org

import java.io.File

import dataset.ProgressState._
import dataset.{DatasetDb, DatasetRepo, Sip, SipFactory}
import harvest.Harvesting.{Harvest, PMHResumptionToken, PublishedDataset, RepoMetadataFormat}
import mapping.{CategoriesRepo, SkosRepo}
import org.OrgActor.DatasetsCountCategories
import org.OrgDb.Dataset
import org.joda.time.DateTime
import play.api.Play.current
import play.api.cache.Cache
import record.EnrichmentParser._
import services.StringHandling._
import services._

import scala.concurrent.duration._
import scala.language.postfixOps

object OrgRepo {

  val SIP_EXTENSION = ".sip.zip"

  lazy val repo = new OrgRepo(NarthexConfig.USER_HOME, NarthexConfig.ORG_ID)

  def pathToDirectory(path: String) = path.replace(":", "_").replace("@", "_")

  case class AvailableSip(file: File) {
    val n = file.getName
    if (!n.endsWith(SIP_EXTENSION)) throw new RuntimeException(s"Strange file name $file")
    val datasetName = n.substring(0, n.indexOf("__"))
    val dateTime = new DateTime(file.lastModified())
  }

}

class OrgRepo(userHome: String, val orgId: String) {

  import org.OrgRepo._

  val root = new File(userHome, "NarthexFiles")
  val orgRoot = new File(root, orgId)
  val datasetsDir = new File(orgRoot, "datasets")
  val rawDir = new File(orgRoot, "raw")
  val mappedDir = new File(orgRoot, "mapped")
  val sipsDir = new File(orgRoot, "sips")
  val categoriesRepo = new CategoriesRepo(new File(orgRoot, "categories"))
  val skosRepo = new SkosRepo(new File(orgRoot, "skos"))
  val factoryDir = new File(orgRoot, "factory")
  val sipFactory = new SipFactory(factoryDir)
  val repoDb = new OrgDb(orgId)

  orgRoot.mkdirs()
  factoryDir.mkdirs()
  datasetsDir.mkdir()
  rawDir.mkdirs()
  mappedDir.mkdirs()
  sipsDir.mkdirs()

  def datasetRepo(datasetName: String): DatasetRepo = {
    val dr = new DatasetRepo(this, datasetName)
    dr.mkdirs
    dr
  }

  def datasetRepoOption(datasetName: String): Option[DatasetRepo] = {
    val dr = datasetRepo(datasetName)
    if (dr.datasetDb.infoOpt.isDefined) Some(dr) else None
  }

  def availableSips: Seq[AvailableSip] = sipsDir.listFiles.toSeq.filter(
    _.getName.endsWith(SIP_EXTENSION)
  ).map(AvailableSip).sortBy(_.dateTime.getMillis).reverse

  def uploadedSips: Seq[Sip] = repoDb.listDatasets.flatMap(dataset => datasetRepo(dataset.datasetName).sipRepo.latestSipOpt)

  def getPublishedDatasets: Seq[PublishedDataset] = {
    repoDb.listDatasets.flatMap { dataset =>
      if (!oaipmhPublishFromInfo(dataset.info))
        None
      else {
        val prefix = DatasetDb.prefixOptFromInfo(dataset.info).getOrElse(throw new RuntimeException(s"No prefix for $dataset)"))
        val namespaces = (dataset.info \ "namespaces" \ "_").map(node => (node.label, node.text))
        val metadataFormat = namespaces.find(_._1 == prefix) match {
          case Some(ns) => RepoMetadataFormat(prefix, ns._2)
          case None => RepoMetadataFormat(prefix)
        }
        //        Logger.info(s"info: ${dataset.info}")
        val recordsPresent = (dataset.info \ "records" \ "ready").text == "true"
        if (!recordsPresent)
          None
        else
          Some(PublishedDataset(
            spec = dataset.datasetName,
            prefix = prefix,
            name = (dataset.info \ "metadata" \ "name").text,
            description = (dataset.info \ "metadata" \ "description").text,
            dataProvider = (dataset.info \ "metadata" \ "dataProvider").text,
            totalRecords = (dataset.info \ "records" \ "recordCount").text.toInt,
            metadataFormat = metadataFormat
          ))
      }
    }
  }

  def getMetadataFormats: Seq[RepoMetadataFormat] = {
    getPublishedDatasets.sortBy(_.metadataFormat.namespace).map(d => (d.prefix, d.metadataFormat)).toMap.values.toSeq
  }

  def getHarvest(resumptionToken: PMHResumptionToken, enriched: Boolean): (List[StoredRecord], Option[PMHResumptionToken]) = {
    Cache.getAs[Harvest](resumptionToken.value).map { harvest =>
      val pageSize = NarthexConfig.OAI_PMH_PAGE_SIZE
      val start = 1 + (harvest.currentPage - 1) * pageSize
      val repo = datasetRepo(harvest.repoName)
      val storedRecords = repo.recordDbOpt.get.recordHarvest(harvest.from, harvest.until, start, pageSize)
      val records = if (enriched) repo.enrichRecords(storedRecords) else parseStoredRecords(storedRecords)
      harvest.next.map { next =>
        Cache.set(next.resumptionToken.value, next, 2 minutes)
        (records, Some(next.resumptionToken))
      } getOrElse {
        (records, None)
      }
    } getOrElse {
      throw new RuntimeException(s"Resumption token not found: $resumptionToken")
    }
  }

  def startCategoryCounts() = {
    val categoryDatasets: Seq[Dataset] = repoDb.listDatasets.flatMap { dataset =>
      val included = (dataset.info \ "categories" \ "included").text
      if (included == "true") Some(dataset) else None
    }
    val datasets = categoryDatasets.map(_.datasetName)
    datasets.foreach(datasetRepo(_).datasetDb.startProgress(CATEGORIZING))
    OrgActor.actor ! DatasetsCountCategories(datasets)
  }
}