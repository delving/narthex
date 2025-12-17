//===========================================================================
//    Copyright 2024 Delving B.V.
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
import play.api.Logging
import play.api.mvc._
import play.api.libs.json._

import organization.OrgContext
import organization.OrgActor.DatasetMessage
import webhook.{IndexingNotification, IndexingComplete}

/**
 * Controller for receiving webhook notifications from Hub3.
 *
 * Hub3 sends notifications after indexing operations complete,
 * including success, warning (verification failed), or error states.
 */
@Singleton
class WebhookController @Inject() (
  orgContext: OrgContext,
  cc: ControllerComponents
)(implicit
  ec: ExecutionContext
) extends AbstractController(cc) with Logging {

  /**
   * Validate webhook API key from Authorization header.
   * Hub3 sends: Authorization: Bearer <api_key>
   */
  private def validateApiKey(request: Request[_]): Boolean = {
    val expectedKey = orgContext.appConfig.webhookApiKey
    if (expectedKey.isEmpty) {
      logger.warn("Webhook API key not configured, rejecting request")
      return false
    }

    request.headers.get("Authorization") match {
      case Some(auth) if auth.startsWith("Bearer ") =>
        val providedKey = auth.stripPrefix("Bearer ").trim
        providedKey == expectedKey
      case _ =>
        false
    }
  }

  /**
   * POST /narthex/webhook/indexing
   * Receives indexing completion notifications from Hub3.
   *
   * Expected payload:
   * {
   *   "type": "success|warning|error",
   *   "orgID": "string",
   *   "datasetID": "string",
   *   "revision": 123,
   *   "timestamp": "2024-01-01T00:00:00Z",
   *   "recordsIndexed": 1000,
   *   "recordsExpected": 1000,
   *   "orphansDeleted": 50,
   *   "errors": [{"documentId": "...", "errorType": "...", "reason": "..."}],
   *   "message": "optional message"
   * }
   */
  def indexingComplete: Action[JsValue] = Action.async(parse.json) { request =>
    // Validate API key
    if (!validateApiKey(request)) {
      logger.warn(s"Webhook request with invalid or missing API key from ${request.remoteAddress}")
      Future.successful(Unauthorized(Json.obj("error" -> "Invalid API key")))
    } else {
      request.body.validate[IndexingNotification] match {
        case JsSuccess(notification, _) =>
          processNotification(notification)
          Future.successful(Ok(Json.obj("status" -> "received")))

        case JsError(errors) =>
          logger.error(s"Failed to parse webhook payload: $errors")
          Future.successful(BadRequest(Json.obj(
            "error" -> "Invalid payload",
            "details" -> JsError.toJson(errors)
          )))
      }
    }
  }

  /**
   * Process the indexing notification:
   * 1. Validate orgID matches configured organization
   * 2. Route to appropriate DatasetActor via OrgActor
   * 3. Actor will update state and broadcast to WebSocket
   */
  private def processNotification(notification: IndexingNotification): Unit = {
    logger.info(s"Received indexing notification for ${notification.datasetID}: " +
      s"type=${notification.`type`}, indexed=${notification.recordsIndexed}, " +
      s"expected=${notification.recordsExpected}, orphans=${notification.orphansDeleted}, " +
      s"errors=${notification.errorCount}")

    // Validate orgID matches configured organization
    if (notification.orgID != orgContext.appConfig.orgId) {
      logger.warn(s"Webhook orgID mismatch: received '${notification.orgID}', " +
        s"expected '${orgContext.appConfig.orgId}'. Ignoring notification.")
      return
    }

    // Create message for DatasetActor
    val message = IndexingComplete(
      notificationType = notification.`type`,
      revision = notification.revision,
      recordsIndexed = notification.recordsIndexed,
      recordsExpected = notification.recordsExpected,
      orphansDeleted = notification.orphansDeleted,
      errorCount = notification.errorCount,
      errors = notification.errors,
      message = notification.message,
      timestamp = notification.timestamp
    )

    // Route to DatasetActor via OrgActor
    // The OrgActor will forward this to the appropriate DatasetActor
    orgContext.orgActor ! DatasetMessage(notification.datasetID, message)

    logger.debug(s"Forwarded IndexingComplete message to DatasetActor for ${notification.datasetID}")
  }
}
