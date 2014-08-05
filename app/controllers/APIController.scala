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
import services._

object APIController extends Controller with TreeHandling with RecordHandling {

  def indexJSON(apiKey: String, email: String, fileName: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        OkFile(Repo(email).fileRepo(fileName).index)
      }
      else {
        Unauthorized
      }
    }
  }

  def indexText(apiKey: String, email: String, fileName: String, path: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
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

  def uniqueText(apiKey: String, email: String, fileName: String, path: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
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

  def histogramText(apiKey: String, email: String, fileName: String, path: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
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

  def rawRecord(apiKey: String, email: String, fileName: String, id: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
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

  def enrichedRecord(apiKey: String, email: String, fileName: String, id: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
        val fileRepo = repo.fileRepo(fileName)
        val filePrefix = s"${email.replaceAllLiterally(".", "_")}/$fileName"
        val mappings = Cache.getAs[Map[String, TargetConcept]](filePrefix).getOrElse {
          val freshMap = fileRepo.getMappings.map(m => (m.source, TargetConcept(m.target, m.vocabulary, m.prefLabel))).toMap
          Cache.set(filePrefix, freshMap, 60 * 5)
          freshMap
        }
        val storedRecord: String = fileRepo.getRecord(id)
        if (storedRecord.nonEmpty) {
          val recordRoot = (fileRepo.getDatasetInfo \ "delimit" \ "recordRoot").text
          val parser = new StoredRecordParser(filePrefix, recordRoot, mappings)
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

  def ids(apiKey: String, email: String, fileName: String, since: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
        val fileRepo = repo.fileRepo(fileName)
        val ids = fileRepo.getIds(since)
        Ok(scala.xml.XML.loadString(ids))
      }
      else {
        Unauthorized
      }
    }
  }

  def mappings(apiKey: String, email: String, fileName: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
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

  def uploadOutput(apiKey: String, email: String, fileName: String) = Action(parse.temporaryFile) {
    // todo: add security
    implicit request => {
      val repo = Repo(email)
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
          "/delving-sip-target/output",
          "/delving-sip-target/output/@id",
          -1
        )
      }
      repo.scanForAnalysisWork()
      Ok
    }
  }

  def uploadSipZip(apiKey: String, email: String, fileName: String) = Action(parse.temporaryFile) {
    // todo: add security
    implicit request =>
      val repo = Repo(email)
      val file = repo.sipZipFile(fileName)
      request.body.moveTo(file, replace = true)
      val zip = new ZipFile(file)
      val datasetFacts = zip.getEntry("dataset_facts.txt")
      val inputStream = zip.getInputStream(datasetFacts)
      val factsFile = new File(file.getParentFile, s"${file.getName}.facts")
      FileUtils.copyInputStreamToFile(inputStream, factsFile)
      Ok
  }

  def listSipZips(apiKey: String, email: String) = Action(parse.anyContent) {
    // todo: add security
    implicit request =>
      val repo = Repo(email)
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
              <versions>
                {for (sv <- facts("schemaVersions").split(", *"))
              yield
                <version>
                  {sv}
                </version>}
              </versions>
            </facts>
          </sip>}
        </sip-list>
      Ok(reply)
  }

  /*
  ame=Afrika Museum
provider=Rijksdienst voor het Cultureelerfgoed
country=Netherlands
dataProvider=Afrika Museum
language=nl
rights=http://creativecommons.org/publicdomain/zero/1.0/
type=IMAGE
dataProviderUri=http://id.musip.nl/crm_e39/8
spec=afrikamuseum
orgId=dimcon
schemaVersions=icn_1.0.3, ese_3.4.0
   */

  private def checkKey(email: String, fileName: String, apiKey: String) = {
    // todo: mindless so far, and do it as an action like in Security.scala
    val toHash: String = s"narthex|$email|$fileName"
    val hash = toHash.hashCode
    val expected: String = Integer.toString(hash, 16).substring(1)
    val correct = expected == apiKey
    if (!correct) {
      println(s"expected[$expected] got[$apiKey] toHash[$toHash]")
    }
    correct
  }
}
