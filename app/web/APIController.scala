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
import org.OrgRepo.{AvailableSip, repo}
import org.apache.commons.io.IOUtils
import play.api.Logger
import play.api.http.ContentTypes
import play.api.libs.json.Json
import play.api.mvc._
import services._
import web.MainController.OkFile

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object APIController extends Controller {

  def listDatasets(apiKey: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
//    val datasets = repo.orgDb.listDatasets.map {
//      dataset =>
//        val lists = DatasetDb.DATASET_PROPERTY_LISTS.flatMap(name => DatasetDb.toJsObjectEntryOption(dataset.info, name))
//        Json.obj("name" -> dataset.spec, "info" -> JsObject(lists))
//    }
//    //      Ok(JsArray(datasets))
//    // todo: this produces a list within a list.  fix it and inform Sjoerd
//    Ok(Json.prettyPrint(Json.arr(datasets))).as(ContentTypes.JSON)
    NotImplemented
  }

  def pathsJSON(apiKey: String, spec: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    val treeFile = repo.datasetRepo(spec).index
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

  def indexJSON(apiKey: String, spec: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    OkFile(repo.datasetRepo(spec).index)
  }

  def indexText(apiKey: String, spec: String, path: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    repo.datasetRepo(spec).indexText(path) match {
      case None =>
        NotFound(s"No index found for $path")
      case Some(file) =>
        OkFile(file)
    }
  }

  def uniqueText(apiKey: String, spec: String, path: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    repo.datasetRepo(spec).uniqueText(path) match {
      case None =>
        NotFound(s"No list found for $path")
      case Some(file) =>
        OkFile(file)
    }
  }

  def histogramText(apiKey: String, spec: String, path: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    repo.datasetRepo(spec).histogramText(path) match {
      case None =>
        NotFound(s"No list found for $path")
      case Some(file) =>
        OkFile(file)
    }
  }

  def termMappings(apiKey: String, spec: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    repo.datasetRepoOption(spec) match {
      case Some(datasetRepo) =>
        val mappings = datasetRepo.termDb.getMappingsRDF
        Ok(mappings)
      case None =>
        NotFound(s"No mappings for $spec")
    }
  }

  def thesaurusMappings(apiKey: String, conceptSchemeA: String, conceptSchemeB: String) = KeyFits(apiKey, parse.anyContent) { implicit request =>
    // todo: should be optional, maybe it doesn't exist
    val thesaurusDb = repo.thesaurusDb(conceptSchemeA, conceptSchemeB)
    val xml = thesaurusDb.getMappingsRDF
    Ok(xml)
  }

  def listSipZips(apiKey: String) = KeyFitsAsync(apiKey, parse.anyContent) { implicit request =>
    val availableSips: Seq[AvailableSip] = repo.availableSips
    repo.uploadedSips.map { uploadedSips =>
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
              <dataset>{ sip.spec }</dataset>
              <file>{ sip.file.getName }</file>
            </sip-zip>
          }
        </uploaded>
      </sip-zips>
      Ok(xml)
    }
  }

  def downloadSipZip(apiKey: String, spec: String) = Action(parse.anyContent) { implicit request =>
    Logger.info(s"Download sip-zip $spec")
    val sipFileOpt = repo.datasetRepoOption(spec).flatMap { datasetRepo =>
      datasetRepo.sipFiles.headOption
    }
    sipFileOpt.map(OkFile(_)).getOrElse(NotFound(s"No sip-zip for $spec"))
  }

  def KeyFits[A](apiKey: String, p: BodyParser[A] = parse.anyContent)(block: Request[A] => Result): Action[A] = Action(p) { implicit request =>
    if (NarthexConfig.apiKeyFits(apiKey)) {
      block(request)
    }
    else {
      Unauthorized(Json.obj("err" -> "Invalid API Access key"))
    }
  }

  def KeyFitsAsync[A](apiKey: String, p: BodyParser[A] = parse.anyContent)(block: Request[A] => Future[Result]): Action[A] = Action.async(p) { implicit request =>
    if (NarthexConfig.apiKeyFits(apiKey)) {
      block(request)
    }
    else {
      Future(Unauthorized(Json.obj("err" -> "Invalid API Access key")))
    }
  }
}
