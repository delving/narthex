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

import controllers.Application.OkFile
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

  def upload(apiKey: String, email: String, fileName: String) = Action(parse.temporaryFile) {
    implicit request => {
      val repo = Repo(email)
      request.body.moveTo(repo.uploadedFile(fileName))
      repo.scanForAnalysisWork()
      val fileRepo = repo.fileRepo(fileName)
      fileRepo.setRecordDelimiter(
        "/delving-sip-target/output",
        "/delving-sip-target/output/@id",
        -1
      )
      Ok
    }
  }

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
