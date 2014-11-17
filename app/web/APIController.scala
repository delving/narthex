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

package web

import java.io.FileInputStream

import analysis.TreeNode
import analysis.TreeNode.ReadTreeNode
import dataset.DatasetOrigin._
import dataset.{DatasetDb, DatasetOrigin, DatasetState}
import harvest.Harvesting.HarvestType.PMH
import org.OrgRepo.repo
import org.apache.commons.io.IOUtils
import play.api.http.ContentTypes
import play.api.libs.json.{JsObject, Json}
import play.api.mvc._
import record.EnrichmentParser
import services.SipRepo._
import services.Temporal.timeToLocalString
import services._
import web.Application.{OkFile, OkXml}
import web.Dashboard._

object APIController extends Controller {

  def listDatasets(apiKey: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    val datasets = repo.repoDb.listDatasets.map {
      dataset =>
        val lists = DATASET_PROPERTY_LISTS.flatMap(name => DatasetDb.toJsObjectEntryOption(dataset.info, name))
        Json.obj("name" -> dataset.datasetName, "info" -> JsObject(lists))
    }
    //      Ok(JsArray(datasets))
    Ok(Json.prettyPrint(Json.arr(datasets))).as(ContentTypes.JSON)
  }

  def pathsJSON(apiKey: String, datasetName: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    val treeFile = repo.datasetRepo(datasetName).index
    val string = IOUtils.toString(new FileInputStream(treeFile))
    val json = Json.parse(string)
    val tree = json.as[ReadTreeNode]
    val paths = TreeNode.gatherPaths(tree, new Call(request.method, request.path).absoluteURL())
    val pathJson = Json.toJson(paths)
    Ok(Json.prettyPrint(pathJson)).as(ContentTypes.JSON)
  }

  def indexJSON(apiKey: String, datasetName: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    OkFile(repo.datasetRepo(datasetName).index)
  }

  def indexText(apiKey: String, datasetName: String, path: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    repo.datasetRepo(datasetName).indexText(path) match {
      case None =>
        NotFound(s"No index found for $path")
      case Some(file) =>
        OkFile(file)
    }
  }

  def uniqueText(apiKey: String, datasetName: String, path: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    repo.datasetRepo(datasetName).uniqueText(path) match {
      case None =>
        NotFound(s"No list found for $path")
      case Some(file) =>
        OkFile(file)
    }
  }

  def histogramText(apiKey: String, datasetName: String, path: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    repo.datasetRepo(datasetName).histogramText(path) match {
      case None =>
        NotFound(s"No list found for $path")
      case Some(file) =>
        OkFile(file)
    }
  }

  def record(apiKey: String, datasetName: String, id: String, enrich: Boolean = false) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    datasetRepo.recordDbOpt.map { recordDb =>
      val storedRecord: String = recordDb.record(id)
      if (storedRecord.nonEmpty) {
        if (enrich) {
          val records = datasetRepo.enrichRecords(storedRecord)
          if (records.nonEmpty) {
            val record = records.head
            OkXml(record.text.toString())
          }
          else {
            NotFound(s"Parser gave no records")
          }
        }
        else {
          val records = EnrichmentParser.parseStoredRecords(storedRecord)
          if (records.nonEmpty) {
            val record = records.head
            OkXml(record.text.toString())
          }
          else {
            NotFound(s"Parser gave no records")
          }
        }
      }
      else {
        NotFound(s"No record found for $id")
      }
    } getOrElse {
      NotFound(s"No record database found for $datasetRepo")
    }
  }

  def rawRecord(apiKey: String, datasetName: String, id: String) = record(apiKey, datasetName, id, enrich = false)

  def enrichedRecord(apiKey: String, datasetName: String, id: String) = record(apiKey, datasetName, id, enrich = true)

  def ids(apiKey: String, datasetName: String, since: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    datasetRepo.recordDbOpt.map { recordDb =>
      val ids = recordDb.getIds(since)
      Ok(scala.xml.XML.loadString(ids))
    } getOrElse {
      NotFound(s"No record database found for $datasetRepo")
    }
  }

  def mappings(apiKey: String, datasetName: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    repo.datasetRepoOption(datasetName) match {
      case Some(datasetRepo) =>
        val mappings = datasetRepo.termDb.getMappings
        val reply =
              <mappings>
                {mappings.map { m =>
                <mapping>
                  <source>{m.source}</source>
                  <target>{m.target}</target>
                  <prefLabel>{m.prefLabel}</prefLabel>
                  <vocabulary>{m.vocabulary}</vocabulary>
                </mapping>
              }}
              </mappings>
        Ok(reply)
      case None =>
        NotFound(s"No mappings for $datasetName")
    }
  }

  def uploadSipZip(apiKey: String, datasetName: String, zipFileName: String) = KeyFits(apiKey, parse.temporaryFile) { implicit request =>
    repo.datasetRepoOption(datasetName).map { datasetRepo =>
      val sipZipFile = datasetRepo.sipRepo.createSipZipFile(zipFileName)
      request.body.moveTo(sipZipFile, replace = true)
      // new zip is now in the sipRepo
      val originOpt: Option[DatasetOrigin] = datasetRepo.sipRepo.latestSipFile.flatMap { sipFile =>
        sipFile.sipMappingOpt.map { sipMapping =>
          (sipFile.harvestUrl, sipFile.harvestSpec, sipFile.harvestPrefix) match {
            case (Some(harvestUrl), Some(harvestSpec), Some(harvestPrefix)) =>
              datasetRepo.datasetDb.setOrigin(SIP_HARVEST, sipMapping.prefix)
              datasetRepo.datasetDb.setHarvestInfo(PMH, harvestUrl, harvestSpec, harvestPrefix)
              datasetRepo.datasetDb.setRecordDelimiter(PMH.recordRoot, PMH.uniqueId, sipFile.recordCount.map(_.toInt).getOrElse(0))
              Some(SIP_HARVEST)
            case _ =>
              datasetRepo.datasetDb.setOrigin(SIP_SOURCE, sipMapping.prefix)
              val recordCount = sipFile.recordCount.map(_.toInt).getOrElse(0)
              datasetRepo.datasetDb.setRecordDelimiter(SIP_SOURCE_RECORD_ROOT, SIP_SOURCE_UNIQUE_ID, recordCount)
              sipFile.copySourceTo(datasetRepo.createIncomingFile("source-from-sip.xml.gz"))
              datasetRepo.datasetDb.setStatus(DatasetState.SOURCED)
              Some(SIP_SOURCE)
          }
        } getOrElse {
          None
        }
      }
      if (originOpt.isDefined) {
        Ok
      }
      else {
        NotFound(s"Unable to determine origin for $datasetName")
      }
    } getOrElse {
      NotFound(s"No dataset named $datasetName")
    }
  }

  def listSipZips(apiKey: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    def reply =
        <sip-list>
          {for (sipZip <- SipRepo.listSipZips)
        yield {
          <sip>
            <file>{sipZip.toString}</file>
            <facts>
              <spec>{sipZip.spec.getOrElse("unknown spec")}</spec>
              <name>{sipZip.name.getOrElse("unknown name")}</name>
              <provider>{sipZip.provider.getOrElse("unknown provider")}</provider>
              <dataProvider>{sipZip.dataProvider.getOrElse("unknown dataProvider")}</dataProvider>
              <country>{sipZip.country.getOrElse("unknown country")}</country>
              <orgId>{sipZip.orgId.getOrElse("unknown orgId")}</orgId>
              <uploadedBy>{sipZip.file.uploadedBy}</uploadedBy>
              <uploadedOn>{timeToLocalString(sipZip.file.uploadedOn)}</uploadedOn>
              <schemaVersions>
                { sipZip.schemaVersionOpt.map { sv =>
                <schemaVersion>
                  <prefix>{sv.split("_")(0)}</prefix>
                  <version>{sv.split("_")(1)}</version>
                </schemaVersion>}}
              </schemaVersions>
            </facts>
          </sip>}}
        </sip-list>
    Ok(reply)
  }

  def downloadSipZip(apiKey: String, datasetName: String) = Action(parse.anyContent) { implicit request =>
    val latestSipFile = repo.datasetRepoOption(datasetName).flatMap { datasetRepo =>
      datasetRepo.sipRepo.latestSipFile
    }
    latestSipFile.map(sipFile => OkFile(sipFile.file.zipFile)).getOrElse(NotFound(s"No SIP Zip file available for dataset $datasetName"))
  }

  def KeyFits[A](apiKey: String, p: BodyParser[A] = parse.anyContent)(block: Request[A] => Result): Action[A] = Action(p) { implicit request =>
    if (NarthexConfig.apiKeyFits(apiKey)) {
      block(request)
    }
    else {
      Unauthorized(Json.obj("err" -> "Invalid API Access key"))
    }
  }
}
