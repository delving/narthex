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
import dataset.SipRepo.AvailableSip
import organization.OrgContext
import org.apache.commons.io.IOUtils
import play.api.http.ContentTypes
import play.api.libs.json.Json
import play.api.mvc._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

class APIController(val orgContext: OrgContext) extends Controller {

  def processingErrorsText(spec: String) = Action(parse.anyContent) { implicit request =>
    val datasetContext = orgContext.datasetContext(spec)
    val latestErrorFile = datasetContext.processedRepo.getLatestErrors
    latestErrorFile.map(Utils.okFile(_)).getOrElse(NotFound(s"No errors found for $spec"))
  }

  def processingSourcedText(spec: String) = Action(parse.anyContent) { implicit request =>
    val datasetContext = orgContext.datasetContext(spec)
    val latestSourcedFile = datasetContext.processedRepo.getLatestSourced
    latestSourcedFile.map(Utils.okFile(_)).getOrElse(NotFound(s"No sourced files found for $spec"))
  }

  def processingProcessedText(spec: String) = Action(parse.anyContent) { implicit request =>
    val datasetContext = orgContext.datasetContext(spec)
    val latestProcessedFiles = datasetContext.processedRepo.getLatestProcessed
    latestProcessedFiles.map(Utils.okFile(_)).getOrElse(NotFound(s"No processed files found for $spec"))
  }

  def processingHarvestingLog(spec: String) = Action(parse.anyContent) { implicit request =>
    val datasetContext = orgContext.datasetContext(spec)
    Some(datasetContext.harvestLogger).map(Utils.okFile(_)).getOrElse(NotFound(s"No processed files found for $spec"))
  }

  def pathsJSON(spec: String) =  Action(parse.anyContent) { implicit request =>
    val treeFile = orgContext.datasetContext(spec).index
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

  def indexJSON(spec: String) = Action(parse.anyContent) { implicit request =>
    Utils.okFile(orgContext.datasetContext(spec).index)
  }

  def uniqueText(spec: String, path: String) = Action(parse.anyContent) { implicit request =>
    orgContext.datasetContext(spec).uniqueText(path) match {
      case None =>
        NotFound(s"No list found for $path")
      case Some(file) =>
        Utils.okFile(file)
    }
  }

  def histogramText(spec: String, path: String) = Action(parse.anyContent) { implicit request =>
    orgContext.datasetContext(spec).histogramText(path) match {
      case None =>
        NotFound(s"No list found for $path")
      case Some(file) =>
        Utils.okFile(file)
    }
  }

  def listSipZips() = Action.async(parse.anyContent) { implicit request =>
    val availableSips: Seq[AvailableSip] = orgContext.availableSips
    orgContext.uploadedSips.map { uploadedSips =>
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

}
