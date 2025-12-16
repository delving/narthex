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

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.libs.json._
import discovery._
import discovery.OaiSourceConfig._

/**
 * Controller for OAI-PMH Dataset Discovery API endpoints.
 */
@Singleton
class DiscoveryController @Inject()(
  cc: ControllerComponents,
  discoveryService: DatasetDiscoveryService
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  private val sourceRepo = discoveryService.sourceRepo

  // --- Source CRUD ---

  /**
   * List all OAI sources.
   */
  def listSources: Action[AnyContent] = Action { request =>
    Ok(Json.toJson(sourceRepo.listSources()))
  }

  /**
   * Get a single source by ID.
   */
  def getSource(id: String): Action[AnyContent] = Action { request =>
    sourceRepo.getSource(id) match {
      case Some(source) => Ok(Json.toJson(source))
      case None => NotFound(Json.obj("error" -> s"Source not found: $id"))
    }
  }

  /**
   * Create a new OAI source.
   */
  def createSource: Action[JsValue] = Action(parse.json) { request =>
    request.body.validate[OaiSource] match {
      case JsSuccess(source, _) =>
        val created = sourceRepo.createSource(source)
        Created(Json.toJson(created))
      case JsError(errors) =>
        BadRequest(Json.obj(
          "error" -> "Invalid source data",
          "details" -> JsError.toJson(errors)
        ))
    }
  }

  /**
   * Update an existing source.
   */
  def updateSource(id: String): Action[JsValue] = Action(parse.json) { request =>
    request.body.validate[OaiSource] match {
      case JsSuccess(source, _) =>
        sourceRepo.updateSource(id, source) match {
          case Some(updated) => Ok(Json.toJson(updated))
          case None => NotFound(Json.obj("error" -> s"Source not found: $id"))
        }
      case JsError(errors) =>
        BadRequest(Json.obj(
          "error" -> "Invalid source data",
          "details" -> JsError.toJson(errors)
        ))
    }
  }

  /**
   * Delete a source.
   */
  def deleteSource(id: String): Action[AnyContent] = Action { request =>
    if (sourceRepo.deleteSource(id)) {
      Ok(Json.obj("deleted" -> id))
    } else {
      NotFound(Json.obj("error" -> s"Source not found: $id"))
    }
  }

  // --- Discovery ---

  /**
   * Discover sets from an OAI-PMH source.
   */
  def discoverSets(id: String): Action[AnyContent] = Action.async { request =>
    discoveryService.discoverSets(id).map {
      case Right(result) => Ok(Json.toJson(result))
      case Left(error) => BadRequest(Json.obj("error" -> error))
    }
  }

  // --- Ignore Management ---

  /**
   * Add setSpecs to the ignore list.
   */
  def ignoreSets(id: String): Action[JsValue] = Action(parse.json) { request =>
    request.body.validate[IgnoreRequest] match {
      case JsSuccess(req, _) =>
        sourceRepo.addIgnoredSets(id, req.setSpecs) match {
          case Some(updated) =>
            Ok(Json.obj(
              "ignored" -> req.setSpecs,
              "totalIgnored" -> updated.ignoredSets.length
            ))
          case None =>
            NotFound(Json.obj("error" -> s"Source not found: $id"))
        }
      case JsError(errors) =>
        BadRequest(Json.obj("error" -> "Invalid request"))
    }
  }

  /**
   * Remove setSpecs from the ignore list.
   */
  def unignoreSets(id: String): Action[JsValue] = Action(parse.json) { request =>
    request.body.validate[IgnoreRequest] match {
      case JsSuccess(req, _) =>
        sourceRepo.removeIgnoredSets(id, req.setSpecs) match {
          case Some(updated) =>
            Ok(Json.obj(
              "unignored" -> req.setSpecs,
              "totalIgnored" -> updated.ignoredSets.length
            ))
          case None =>
            NotFound(Json.obj("error" -> s"Source not found: $id"))
        }
      case JsError(errors) =>
        BadRequest(Json.obj("error" -> "Invalid request"))
    }
  }

  // --- Import ---

  /**
   * Import selected sets as new datasets.
   */
  def importSets: Action[JsValue] = Action.async(parse.json) { request =>
    request.body.validate[List[SetImportRequest]] match {
      case JsSuccess(requests, _) =>
        if (requests.isEmpty) {
          Future.successful(BadRequest(Json.obj("error" -> "No sets to import")))
        } else {
          discoveryService.importSets(requests).map { results =>
            val successful = results.filter(_.success)
            val failed = results.filterNot(_.success)

            Ok(Json.obj(
              "imported" -> successful.length,
              "failed" -> failed.length,
              "results" -> Json.toJson(results)
            ))
          }
        }
      case JsError(errors) =>
        Future.successful(BadRequest(Json.obj(
          "error" -> "Invalid import request",
          "details" -> JsError.toJson(errors)
        )))
    }
  }

  /**
   * Preview spec transformation.
   */
  def previewSpecTransform: Action[JsValue] = Action(parse.json) { request =>
    (request.body \ "setSpec").asOpt[String] match {
      case Some(setSpec) =>
        val normalized = sourceRepo.normalizeSetSpec(setSpec)
        Ok(Json.obj(
          "original" -> setSpec,
          "normalized" -> normalized
        ))
      case None =>
        BadRequest(Json.obj("error" -> "Missing setSpec"))
    }
  }
}
