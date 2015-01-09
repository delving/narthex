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
import services._
import web.MainController.OkFile

object APIController extends Controller {

  def listDatasets(apiKey: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    val datasets = repo.orgDb.listDatasets.map {
      dataset =>
        val lists = AppController.DATASET_PROPERTY_LISTS.flatMap(name => DatasetDb.toJsObjectEntryOption(dataset.info, name))
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

  def termMappings(apiKey: String, datasetName: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    repo.datasetRepoOption(datasetName) match {
      case Some(datasetRepo) =>
        val mappings = datasetRepo.termDb.getMappingsRDF
        Ok(mappings)
      case None =>
        NotFound(s"No mappings for $datasetName")
    }
  }

  def thesaurusMappings(apiKey: String, conceptSchemeA: String, conceptSchemeB: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    // todo: should be optional, maybe it doesn't exist
    val thesaurusDb = repo.thesaurusDb(conceptSchemeA, conceptSchemeB)
    val xml = thesaurusDb.getMappingsRDF
    Ok(xml)
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
