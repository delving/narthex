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

package controllers

import java.io.{File, FileInputStream}
import java.util.zip.ZipFile

import controllers.Application.{OkFile, OkXml}
import org.apache.commons.io.FileUtils.deleteQuietly
import org.apache.commons.io.{FileUtils, IOUtils}
import play.api.libs.json.Json
import play.api.mvc._
import services.DatasetState._
import services.Repo.repo
import services._

object APIController extends Controller with TreeHandling with RecordHandling {

  def pathsJSON(apiKey: String, fileName: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
      val treeFile = repo.datasetRepo(fileName).index
      val string = IOUtils.toString(new FileInputStream(treeFile))
      val json= Json.parse(string)
      val tree = json.as[ReadTreeNode]
      val paths = gatherPaths(tree, new Call(request.method, request.path).absoluteURL())
      Ok(Json.toJson(paths))
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
      val uploaded: File = repo.uploadedFile(fileName)
      deleteQuietly(uploaded)
      request.body.moveTo(uploaded)
      val datasetRepo = repo.datasetRepo(fileName)
      datasetRepo.datasetDb.createDataset(READY)
      datasetRepo.datasetDb.setOrigin(DatasetOrigin.SIP, "?") // connect with sip somehow
      datasetRepo.datasetDb.setRecordDelimiter(
        recordRoot = "/delving-sip-target/output",
        uniqueId = "/delving-sip-target/output/@id",
        recordCount = -1
      )
      Ok
    }
  }

  def uploadSipZip(apiKey: String, fileName: String) = KeyFits(apiKey, parse.temporaryFile) {
    implicit request =>
      val file = repo.sipZipFile(fileName)
      val factsFile = repo.sipZipFactsFile(fileName)
      request.body.moveTo(file, replace = true)
      val zip = new ZipFile(file)
      val datasetFacts = zip.getEntry("dataset_facts.txt")
      val inputStream = zip.getInputStream(datasetFacts)
      FileUtils.copyInputStreamToFile(inputStream, factsFile)
      Ok
  }

  def listSipZips(apiKey: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request =>
      def reply =
        <sip-list>
          {for ((file, factsFile, facts) <- repo.listSipZip)
        yield
          <sip>
            <file>{file.getName}</file>
            <facts>
              <spec>{facts("spec")}</spec>
              <name>{facts("name")}</name>
              <provider>{facts("provider")}</provider>
              <dataProvider>{facts("dataProvider")}</dataProvider>
              <country>{facts("country")}</country>
              <orgId>{facts("orgId")}</orgId>
              <uploadedBy>{facts("uploadedBy")}</uploadedBy>
              <uploadedOn>{facts("uploadedOn")}</uploadedOn>
              <schemaVersions>
                {for (sv <- facts("schemaVersions").split(", *"))
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
      OkFile(repo.sipZipFile(fileName))
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
