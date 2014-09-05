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

import java.io.File
import java.util.zip.ZipFile

import controllers.Application.OkFile
import org.apache.commons.io.FileUtils
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.json.Json
import play.api.mvc._
import services.Repo.repo
import services._

import scala.xml.transform.{RewriteRule, RuleTransformer}
import scala.xml.{Elem, Node}

object APIController extends Controller with TreeHandling with RecordHandling {

  def indexJSON(apiKey: String, fileName: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
      OkFile(repo.fileRepo(fileName).index)
    }
  }

  def indexText(apiKey: String, fileName: String, path: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
        repo.fileRepo(fileName).indexText(path) match {
          case None =>
            NotFound(s"No index found for $path")
          case Some(file) =>
            OkFile(file)
        }
    }
  }

  def uniqueText(apiKey: String, fileName: String, path: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
        repo.fileRepo(fileName).uniqueText(path) match {
          case None =>
            NotFound(s"No list found for $path")
          case Some(file) =>
            OkFile(file)
        }
    }
  }

  def histogramText(apiKey: String, fileName: String, path: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
        repo.fileRepo(fileName).histogramText(path) match {
          case None =>
            NotFound(s"No list found for $path")
          case Some(file) =>
            OkFile(file)
        }
    }
  }

  def rawRecord(apiKey: String, fileName: String, id: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
        val fileRepo = repo.fileRepo(fileName)
        val storedRecord: Elem = fileRepo.recordRepo.record(id)
        if (storedRecord.nonEmpty) {
          Ok(storedRecord)
        }
        else {
          NotFound(s"No record found for $id")
        }
    }
  }

  // todo: use something like this for enrichment
  class Stamp(prefix: String, localName: String) extends RewriteRule {
    override def transform(node: Node): Node = node match {
      case elem: Elem if elem.label == "Identifier" =>
        <hello>helllo</hello>
      case remainder => remainder
    }
  }

  class Stamper(prefix: String, localName: String) extends RuleTransformer(new Stamp(prefix, localName))

  def enrichedRecord(apiKey: String, fileName: String, id: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
        val fileRepo = repo.fileRepo(fileName)
        val termRepo = fileRepo.termRepo
        val mappings = Cache.getAs[Map[String, TargetConcept]](fileName).getOrElse {
          val freshMap = termRepo.getMappings.map(m => (m.source, TargetConcept(m.target, m.vocabulary, m.prefLabel))).toMap
          Cache.set(fileName, freshMap, 60 * 5)
          freshMap
        }
        val storedRecord: Elem = fileRepo.recordRepo.record(id)
        if (storedRecord.nonEmpty) {
          val filePrefix = s"${NarthexConfig.ORG_ID}/$fileName"
          val parser = new StoredRecordParser(filePrefix, mappings)
          // todo: use something like Stamper above to find a way to iterate through the elem instead
          val record = parser.parse(storedRecord.toString())
          Ok(scala.xml.XML.loadString(record.text.toString()))
        }
        else {
          NotFound(s"No record found for $id")
        }
    }
  }

  def ids(apiKey: String, fileName: String, since: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
        val fileRepo = repo.fileRepo(fileName)
        val ids = fileRepo.recordRepo.getIds(since)
        Ok(scala.xml.XML.loadString(ids))
    }
  }

  def mappings(apiKey: String, fileName: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request => {
        repo.fileRepoOption(fileName) match {
          case Some(fileRepo) =>
            val mappings = fileRepo.termRepo.getMappings
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
      if (uploaded.exists()) {
        val fileRepo = repo.fileRepo(fileName)
        fileRepo.delete()
        request.body.moveTo(uploaded)
      }
      else {
        request.body.moveTo(uploaded)
        val fileRepo = repo.fileRepo(fileName)
        fileRepo.datasetDb.setRecordDelimiter(
          recordRoot = "/delving-sip-target/output",
          uniqueId = "/delving-sip-target/output/@id",
          recordCount = -1
        )
        fileRepo.startAnalysis()
      }
      Ok
    }
  }

  def uploadSipZip(apiKey: String, fileName: String) = KeyFits(apiKey, parse.temporaryFile) {
    implicit request =>
      val file = repo.sipZipFile(fileName)
      request.body.moveTo(file, replace = true)
      val zip = new ZipFile(file)
      val datasetFacts = zip.getEntry("dataset_facts.txt")
      val inputStream = zip.getInputStream(datasetFacts)
      val factsFile = new File(file.getParentFile, s"${file.getName}.facts")
      FileUtils.copyInputStreamToFile(inputStream, factsFile)
      Ok
  }

  def listSipZips(apiKey: String) = KeyFits(apiKey, parse.anyContent) {
    implicit request =>
      def reply =
        <sip-list>
          {for ((file, facts) <- repo.listSipZip)
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
