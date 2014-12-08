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
import dataset.{DatasetDb, Sip}
import org.OrgRepo.{AvailableSip, repo}
import org.apache.commons.io.IOUtils
import play.api.Logger
import play.api.http.ContentTypes
import play.api.libs.json.{JsObject, Json}
import play.api.mvc._
import record.EnrichmentParser
import services._
import web.Application.{OkFile, OkXml}

object APIController extends Controller {

  def listDatasets(apiKey: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    val datasets = repo.repoDb.listDatasets.map {
      dataset =>
        val lists = Dashboard.DATASET_PROPERTY_LISTS.flatMap(name => DatasetDb.toJsObjectEntryOption(dataset.info, name))
        Json.obj("name" -> dataset.datasetName, "info" -> JsObject(lists))
    }
    //      Ok(JsArray(datasets))
    // todo: this produces a list within a list.  fix it and inform Sjoerd
    Ok(Json.prettyPrint(Json.arr(datasets))).as(ContentTypes.JSON)
  }

  def pathsJSON(apiKey: String, datasetName: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    val treeFile = repo.datasetRepo(datasetName).index
    if (treeFile.exists()) {
      val string = IOUtils.toString(new FileInputStream(treeFile))
      val json = Json.parse(string)
      val tree = json.as[ReadTreeNode]
      val paths = TreeNode.gatherPaths(tree, new Call(request.method, request.path).absoluteURL())
      val pathJson = Json.toJson(paths)
      Ok(Json.prettyPrint(pathJson)).as(ContentTypes.JSON)
    }
    else {
      Ok("{ 'problem': 'nothing found' }").as(ContentTypes.JSON)
    }
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

/**
 *
 * todo: turn this into RDF:
<mappings>
  <term-mapping>
    <sourceURI>dimcon/afrika_sip_drop/rdf:Description/dc:subject/maten%20en%20gewichten</sourceURI>
    <targetURI>http://data.cultureelerfgoed.nl/semnet/1cf92d41-21eb-4a17-b132-25d407ff01a4</targetURI>
    <conceptScheme>Erfgoed Thesaurus</conceptScheme>
    <prefLabel>weefgewichten</prefLabel>
    <who>info@delving.eu</who>
    <when>2014-11-06T07:44:08+01:00</when>
  </mapping>
</term-mappings>
something like this:
 <skos:Concept rdf:about="dimcon/afrika_sip_drop/rdf:Description/dc:subject/maten%20en%20gewichten">
     <skos:exactMatch rdf:resource="http://data.cultureelerfgoed.nl/semnet/1cf92d41-21eb-4a17-b132-25d407ff01a4"
     <skos:prefLabel>weefgewichten</skos:prefLabel>
     <skos:note>Mapped by {{ username }} on {{ date }}</skos:note>
     <skos:note>https://github.com/delving/narthex</skos:note>
 </skos:Concept>
 */

def mappings(apiKey: String, datasetName: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    repo.datasetRepoOption(datasetName) match {
      case Some(datasetRepo) =>
        val mappings = datasetRepo.termDb.getMappings
        val reply =
              <mappings>
                {mappings.map { m =>
                <mapping>
                  <sourceURI>{m.sourceURI}</sourceURI>
                  <targetURI>{m.targetURI}</targetURI>
                  <prefLabel>{m.prefLabel}</prefLabel>
                  <conceptScheme>{m.conceptScheme}</conceptScheme>
                  <who>{m.who}</who>
                  <when>{m.whenString}</when>
                </mapping>
              }}
              </mappings>
        Ok(reply)
      case None =>
        NotFound(s"No mappings for $datasetName")
    }
  }

  def listSipZips(apiKey: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    val availableSips: Seq[AvailableSip] = repo.availableSips
    val uploadedSips: Seq[Sip] = repo.uploadedSips
    val xml =
      <sip-zips>
        <available>
          {
            for (availableSip <- availableSips) yield
            <sip-zip>
              <dataset>{ availableSip.datasetName }</dataset>
              <file>{ availableSip.file.getName }</file>
            </sip-zip>
          }
        </available>
        <uploaded>
          {
            for (sip <- uploadedSips) yield
            <sip-zip>
              <dataset>{ sip.datasetName }</dataset>
              <file>{ sip.file.getName }</file>
            </sip-zip>
          }
        </uploaded>
      </sip-zips>
    Ok(xml)
  }

  def downloadSipZip(apiKey: String, datasetName: String) = Action(parse.anyContent) { implicit request =>
    Logger.info(s"Download sip-zip $datasetName")
    val sipFileOpt = repo.datasetRepoOption(datasetName).flatMap { datasetRepo =>
      datasetRepo.sipFiles.headOption
    }
    sipFileOpt.map(OkFile(_)).getOrElse(NotFound(s"No sip-zip for $datasetName"))
  }

  def uploadSipZip(apiKey: String, datasetName: String, zipFileName: String) = KeyFits(apiKey, parse.temporaryFile) { implicit request =>
    repo.datasetRepoOption(datasetName).map { datasetRepo =>
      request.body.moveTo(datasetRepo.sipRepo.createSipZipFile(zipFileName))
      datasetRepo.dropTree()
      datasetRepo.dropSource()
      datasetRepo.startSourceGeneration()
      Ok
    } getOrElse {
      NotAcceptable(Json.obj("err" -> s"Dataset $datasetName not found"))
    }
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
