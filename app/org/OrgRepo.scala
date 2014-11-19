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
import dataset.{DatasetOrigin, DatasetRepo}
import harvest.Harvesting.{Harvest, PMHResumptionToken, PublishedDataset, RepoMetadataFormat}
import mapping.{CategoriesRepo, SkosRepo}
import org.OrgActor.DatasetsCountCategories
import org.OrgDb.Dataset
import play.api.Logger
import play.api.Play.current
import play.api.cache.Cache
import record.EnrichmentParser._
import services.StringHandling._
import services._

import scala.concurrent.duration._
import scala.language.postfixOps

object OrgRepo {

  lazy val repo = new OrgRepo(NarthexConfig.USER_HOME, NarthexConfig.ORG_ID)

  def pathToDirectory(path: String) = path.replace(":", "_").replace("@", "_")

}

class OrgRepo(userHome: String, val orgId: String) {
  val root = new File(userHome, "NarthexFiles")
  val orgRoot = new File(root, orgId)
  val datasetsDir = new File(orgRoot, "dastasets")
  val categoriesRepo = new CategoriesRepo(new File(orgRoot, "categories"))
  val skosRepo = new SkosRepo(new File(orgRoot, "skos"))
  val repoDb = new OrgDb(orgId)

  orgRoot.mkdirs()
  datasetsDir.mkdir()

  def datasetRepo(datasetName: String): DatasetRepo = {
    val dr = new DatasetRepo(this, datasetName)
    dr.mkdirs
    dr
  }

  def datasetRepoOption(datasetName: String): Option[DatasetRepo] = {
    val dr = datasetRepo(datasetName)
    if (dr.datasetDb.infoOpt.isDefined) Some(dr) else None
  }

  def getPublishedDatasets: Seq[PublishedDataset] = {
    repoDb.listDatasets.flatMap { dataset =>
      if (!oaipmhPublishFromInfo(dataset.info))
        None
      else {
        val prefix = DatasetOrigin.prefixFromInfo(dataset.info)
        val namespaces = (dataset.info \ "namespaces" \ "_").map(node => (node.label, node.text))
        val metadataFormat = namespaces.find(_._1 == prefix) match {
          case Some(ns) => RepoMetadataFormat(prefix, ns._2)
          case None => RepoMetadataFormat(prefix)
        }
        Logger.info(s"info: ${dataset.info}")
        val recordsPresent = (dataset.info \ "records" \ "time").nonEmpty
        if (!recordsPresent)
          None
        else
          Some(PublishedDataset(
            spec = dataset.datasetName,
            prefix = prefix,
            name = (dataset.info \ "metadata" \ "name").text,
            description = (dataset.info \ "metadata" \ "description").text,
            dataProvider = (dataset.info \ "metadata" \ "dataProvider").text,
            totalRecords = (dataset.info \ "delimit" \ "recordCount").text.toInt,
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