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

package webhook

import play.api.libs.json._

/**
 * Represents an error that occurred during indexing in Hub3.
 */
case class IndexingError(
  documentId: String,
  errorType: String,
  reason: String
)

object IndexingError {
  implicit val format: Format[IndexingError] = Json.format[IndexingError]
}

/**
 * Webhook payload from Hub3 after indexing completes.
 * This notification is sent after orphan control runs.
 */
case class IndexingNotification(
  `type`: String,              // "success", "warning", "error"
  orgID: String,
  datasetID: String,
  revision: Int,
  timestamp: String,           // ISO 8601 format
  recordsIndexed: Int,
  recordsExpected: Int,
  orphansDeleted: Int,
  errors: Option[Seq[IndexingError]],
  message: Option[String]
) {
  def isSuccess: Boolean = `type` == "success"
  def isWarning: Boolean = `type` == "warning"
  def isError: Boolean = `type` == "error"
  def errorCount: Int = errors.map(_.size).getOrElse(0)
}

object IndexingNotification {
  implicit val format: Format[IndexingNotification] = Json.format[IndexingNotification]
}

/**
 * Message sent to DatasetActor when indexing notification is received.
 * This is the internal representation used within the actor system.
 */
case class IndexingComplete(
  notificationType: String,
  revision: Int,
  recordsIndexed: Int,
  recordsExpected: Int,
  orphansDeleted: Int,
  errorCount: Int,
  errors: Option[Seq[IndexingError]],
  message: Option[String],
  timestamp: String
)
