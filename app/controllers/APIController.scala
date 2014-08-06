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
import play.api.mvc._
import services.Repo.repo
import services._

object APIController extends Controller with TreeHandling with RecordHandling {

  def indexJSON(apiKey: String, fileName: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(fileName, apiKey)) {
        OkFile(repo.fileRepo(fileName).index)
      }
      else {
        Unauthorized
      }
    }
  }

  def indexText(apiKey: String, fileName: String, path: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(fileName, apiKey)) {
        repo.fileRepo(fileName).indexText(path) match {
          case None =>
            NotFound(s"No index found for $path")
          case Some(file) =>
            OkFile(file)
        }
      }
      else {
        Unauthorized
      }
    }
  }

  def uniqueText(apiKey: String, fileName: String, path: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(fileName, apiKey)) {
        repo.fileRepo(fileName).uniqueText(path) match {
          case None =>
            NotFound(s"No list found for $path")
          case Some(file) =>
            OkFile(file)
        }
      }
      else {
        Unauthorized
      }
    }
  }

  def histogramText(apiKey: String, fileName: String, path: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(fileName, apiKey)) {
        repo.fileRepo(fileName).histogramText(path) match {
          case None =>
            NotFound(s"No list found for $path")
          case Some(file) =>
            OkFile(file)
        }
      }
      else {
        Unauthorized
      }
    }
  }

  def rawRecord(apiKey: String, fileName: String, id: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(fileName, apiKey)) {
        val fileRepo = repo.fileRepo(fileName)
        val storedRecord: String = fileRepo.getRecord(id)
        if (storedRecord.nonEmpty) {
          Ok(scala.xml.XML.loadString(storedRecord))
        }
        else {
          NotFound(s"No record found for $id")
        }
      }
      else {
        Unauthorized
      }
    }
  }

  def enrichedRecord(apiKey: String, fileName: String, id: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(fileName, apiKey)) {
        val fileRepo = repo.fileRepo(fileName)
        val mappings = Cache.getAs[Map[String, TargetConcept]](fileName).getOrElse {
          val freshMap = fileRepo.getMappings.map(m => (m.source, TargetConcept(m.target, m.vocabulary, m.prefLabel))).toMap
          Cache.set(fileName, freshMap, 60 * 5)
          freshMap
        }
        val storedRecord: String = fileRepo.getRecord(id)
        if (storedRecord.nonEmpty) {
          val recordRoot = (fileRepo.getDatasetInfo \ "delimit" \ "recordRoot").text
          val parser = new StoredRecordParser(fileName, recordRoot, mappings)
          val record = parser.parse(storedRecord)
          Ok(scala.xml.XML.loadString(record.text.toString()))
        }
        else {
          NotFound(s"No record found for $id")
        }
      }
      else {
        Unauthorized
      }
    }
  }

  def ids(apiKey: String, fileName: String, since: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(fileName, apiKey)) {
        val fileRepo = repo.fileRepo(fileName)
        val ids = fileRepo.getIds(since)
        Ok(scala.xml.XML.loadString(ids))
      }
      else {
        Unauthorized
      }
    }
  }

  def mappings(apiKey: String, fileName: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(fileName, apiKey)) {
        repo.fileRepoOption(fileName) match {
          case Some(fileRepo) =>
            val mappings = fileRepo.getMappings
            val reply =
              <mappings>
                {mappings.map { m =>
                <mapping>
                  <source>
                    {m.source}
                  </source>
                  <target>
                    {m.target}
                  </target>
                </mapping>
              }}
              </mappings>
            Ok(reply)
          case None =>
            NotFound(s"No mappings for $fileName")
        }
      }
      else {
        Unauthorized("Unauthorized")
      }
    }
  }

  def uploadOutput(apiKey: String, fileName: String) = Action(parse.temporaryFile) {
    // todo: add security
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
        fileRepo.setRecordDelimiter(
          recordRoot = "/delving-sip-target/output",
          uniqueId = "/delving-sip-target/output/@id",
          recordCount = -1
        )
      }
      repo.scanForAnalysisWork()
      Ok
    }
  }

  def uploadSipZip(apiKey: String, fileName: String) = Action(parse.temporaryFile) {
    // todo: add security
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

  def listSipZips(apiKey: String) = Action(parse.anyContent) {
    // todo: add security
    implicit request =>
      def reply =
        <sip-list>
          {for ((file, facts) <- repo.listSipZip)
        yield
          <sip>
            <file>
              {file.getName}
            </file>
            <facts>
              <name>
                {facts("name")}
              </name>
              <dataProvider>
                {facts("dataProvider")}
              </dataProvider>
              <country>
                {facts("dataProvider")}
              </country>
              <orgId>
                {facts("orgId")}
              </orgId>
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

  private def checkKey(fileName: String, apiKey: String) = {
    // todo: mindless so far, and do it as an action like in Security.scala
    val toHash: String = s"narthex|$fileName"
    val hash = toHash.hashCode
    val expected: String = Integer.toString(hash, 16).substring(1)
    val correct = expected == apiKey
    if (!correct) {
      println(s"expected[$expected] got[$apiKey] toHash[$toHash]")
    }
    correct
  }
}
