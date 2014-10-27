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
import java.util.zip.ZipFile

import analysis.TreeHandling
import dataset.DatasetState._
import dataset.{DatasetDb, DatasetOrigin}
import org.OrgRepo.repo
import org.apache.commons.io.{FileUtils, IOUtils}
import play.api.http.ContentTypes
import play.api.libs.json.{JsObject, Json}
import play.api.mvc._
import record.RecordHandling
import services._
import web.Application.{OkFile, OkXml}
import web.Dashboard._

object APIController extends Controller with TreeHandling with RecordHandling {

  def listDatasets(apiKey: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
      val datasets = repo.repoDb.listDatasets.map {
        dataset =>
          val lists = DATASET_PROPERTY_LISTS.flatMap(name => DatasetDb.toJsObjectEntryOption(dataset.info, name))
          Json.obj("name" -> dataset.name, "info" -> JsObject(lists))
      }
      //      Ok(JsArray(datasets))
      Ok(Json.prettyPrint(Json.arr(datasets))).as(ContentTypes.JSON)
    }
  }


  def pathsJSON(apiKey: String, fileName: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
      val treeFile = repo.datasetRepo(fileName).index
      val string = IOUtils.toString(new FileInputStream(treeFile))
      val json = Json.parse(string)
      val tree = json.as[ReadTreeNode]
      val paths = gatherPaths(tree, new Call(request.method, request.path).absoluteURL())
      val pathJson = Json.toJson(paths)
      Ok(Json.prettyPrint(pathJson)).as(ContentTypes.JSON)
    }
  }

  def indexJSON(apiKey: String, fileName: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
      OkFile(repo.datasetRepo(fileName).index)
    }
  }

  def indexText(apiKey: String, fileName: String, path: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
      repo.datasetRepo(fileName).indexText(path) match {
        case None =>
          NotFound(s"No index found for $path")
        case Some(file) =>
          OkFile(file)
      }
    }
  }

  def uniqueText(apiKey: String, fileName: String, path: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
      repo.datasetRepo(fileName).uniqueText(path) match {
        case None =>
          NotFound(s"No list found for $path")
        case Some(file) =>
          OkFile(file)
      }
    }
  }

  def histogramText(apiKey: String, fileName: String, path: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
      repo.datasetRepo(fileName).histogramText(path) match {
        case None =>
          NotFound(s"No list found for $path")
        case Some(file) =>
          OkFile(file)
      }
    }
  }

  def record(apiKey: String, fileName: String, id: String, enrich: Boolean = false) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
      val datasetRepo = repo.datasetRepo(fileName)
      val storedRecord: String = datasetRepo.recordDb.record(id)
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
          val records = parseStoredRecords(storedRecord)
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
    }
  }

  def rawRecord(apiKey: String, fileName: String, id: String) = record(apiKey, fileName, id, enrich = false)

  def enrichedRecord(apiKey: String, fileName: String, id: String) = record(apiKey, fileName, id, enrich = true)

  def ids(apiKey: String, fileName: String, since: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
      val datasetRepo = repo.datasetRepo(fileName)
      val ids = datasetRepo.recordDb.getIds(since)
      Ok(scala.xml.XML.loadString(ids))
    }
  }

  def mappings(apiKey: String, fileName: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
      repo.datasetRepoOption(fileName) match {
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
          NotFound(s"No mappings for $fileName")
      }
    }
  }

  def uploadOutput(apiKey: String, fileName: String) = KeyFits(apiKey, parse.temporaryFile) {
    implicit request => {
      val datasetRepo = repo.datasetRepo(fileName)
      request.body.moveTo(datasetRepo.createIncomingFile(request.body.file.getName), replace = true)
      datasetRepo.datasetDb.createDataset(READY)
      datasetRepo.datasetDb.setOrigin(DatasetOrigin.SIP)
      datasetRepo.datasetDb.setRecordDelimiter(
        recordRoot = "/rdf:RDF/rdf:Description",
        uniqueId = "/rdf:RDF/rdf:Description/@rdf:about",
        recordCount = -1
      )
      datasetRepo.startAnalysis()
      Ok
    }
  }

  def uploadSipZip(apiKey: String, fileName: String) = KeyFits(apiKey, parse.temporaryFile) {
    implicit request =>
      val file = repo.createSipZipFile(fileName)
      request.body.moveTo(file, replace = true)
      val zip = new ZipFile(file)
      val factsEntry = zip.getEntry("dataset_facts.txt")
      val factsFile = repo.createSipZipFactsFile(fileName)
      FileUtils.copyInputStreamToFile(zip.getInputStream(factsEntry), factsFile)
      val facts = repo.readMapFile(factsFile)
      val hintsEntry = zip.getEntry("hints.txt")
      val hintsFile = repo.createSipZipHintsFile(fileName)
      FileUtils.copyInputStreamToFile(zip.getInputStream(hintsEntry), hintsFile)
      val hints = repo.readMapFile(hintsFile)
      // have facts and hints - push them to datasetDb
      val prefixes = for (sv <- facts("schemaVersions").split(", *")) yield sv.split("_")(0)
      val datasetNames = prefixes.map(p => s"${facts("spec")}__$p")
      val datasetRepos = datasetNames.flatMap(repo.datasetRepoOption)
      datasetRepos.foreach { r =>
        val db = r.datasetDb
        db.setSipFacts(facts)
        db.setSipHints(hints)
        db.infoOption.foreach { info =>
          val description = info \ "description"
          if (description.isEmpty) {
            val initialMeta = Map(
              "name" -> facts("name"),
              "dataProvider" -> facts("dataProvider")
            )
            db.setMetadata(initialMeta)
          }
        }
      }
      Ok
  }

  def listSipZips(apiKey: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request =>
      def reply =
        <sip-list>
          {for (sipZip <- repo.listSipZips)
        yield
          <sip>
            <file>{sipZip.toString}</file>
            <facts>
              <spec>{sipZip.facts("spec")}</spec>
              <name>{sipZip.facts("name")}</name>
              <provider>{sipZip.facts("provider")}</provider>
              <dataProvider>{sipZip.facts("dataProvider")}</dataProvider>
              <country>{sipZip.facts("country")}</country>
              <orgId>{sipZip.facts("orgId")}</orgId>
              <uploadedBy>{sipZip.uploadedBy}</uploadedBy>
              <uploadedOn>{sipZip.uploadedOn}</uploadedOn>
              <schemaVersions>
                {for (sv <- sipZip.facts("schemaVersions").split(", *"))
              yield
                <schemaVersion>
                  <prefix>{sv.split("_")(0)}</prefix>
                  <version>{sv.split("_")(1)}</version>
                </schemaVersion>}
              </schemaVersions>
            </facts>
          </sip>}
        </sip-list>
      Ok(reply)
  }

  def downloadSipZip(apiKey: String, fileName: String) = Action(parse.anyContent) {
    implicit request =>
      OkFile(repo.createSipZipFile(fileName))
  }

  def KeyFits[A](apiKey: String, p: BodyParser[A] = parse.anyContent)(block: Request[A] => SimpleResult): Action[A] = Action(p) {
    implicit request =>
      if (NarthexConfig.apiKeyFits(apiKey)) {
        block(request)
      }
      else {
        Unauthorized(Json.obj("err" -> "Invalid API Access key"))
      }
  }
}
