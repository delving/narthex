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

package services

import java.io.File
import org.joda.time.DateTime
import play.api.libs.json._
import services.FileHandling.appender
import services.Temporal.timeToString

/**
 * Service for logging dataset activity to JSONL files.
 * Tracks operations (harvest, process, save, etc.) with metadata about duration,
 * record counts, and whether triggered manually or automatically.
 *
 * For automatic workflows (e.g., periodic harvest), operations are grouped using
 * a workflow_id to show the complete pipeline as a single logical activity.
 */
object ActivityLogger {

  /**
   * Start a new workflow (for automatic triggers that run multiple operations).
   *
   * @param activityLog File to append to
   * @param workflowId Unique identifier for this workflow
   * @param triggerType Type of automatic trigger ("periodic", "retry", etc.)
   * @param operations List of operations that will be executed
   */
  def startWorkflow(
    activityLog: File,
    workflowId: String,
    triggerType: String,
    operations: Seq[String]
  ): Unit = {
    val entry = Json.obj(
      "timestamp" -> timeToString(DateTime.now()),
      "workflow_id" -> workflowId,
      "trigger" -> "automatic",
      "trigger_type" -> triggerType,
      "status" -> "started",
      "operations" -> operations
    )
    appendEntry(activityLog, entry)
  }

  /**
   * Log the start of an operation.
   *
   * @param activityLog File to append to
   * @param operation Operation name (HARVEST, PROCESS, SAVE, etc.)
   * @param trigger "manual" or "automatic"
   * @param workflowId Optional workflow ID for automatic workflows
   * @param metadata Optional additional metadata
   */
  def logOperationStart(
    activityLog: File,
    operation: String,
    trigger: String,
    workflowId: Option[String] = None,
    metadata: Map[String, JsValue] = Map.empty
  ): Unit = {
    var entry = Json.obj(
      "timestamp" -> timeToString(DateTime.now()),
      "operation" -> operation,
      "status" -> "started"
    )

    // Add workflow_id if this is part of an automatic workflow
    workflowId.foreach(id => entry = entry + ("workflow_id" -> JsString(id)))

    // For standalone operations (manual or non-workflow automatic), include trigger
    if (workflowId.isEmpty) {
      entry = entry + ("trigger" -> JsString(trigger))
    }

    // Add any additional metadata
    if (metadata.nonEmpty) {
      entry = entry + ("metadata" -> JsObject(metadata))
    }

    appendEntry(activityLog, entry)
  }

  /**
   * Log the completion of an operation.
   *
   * @param activityLog File to append to
   * @param operation Operation name
   * @param trigger "manual" or "automatic"
   * @param startTime When the operation started
   * @param workflowId Optional workflow ID for automatic workflows
   * @param recordCount Optional number of records processed
   * @param metadata Optional additional metadata (validRecords, invalidRecords, etc.)
   */
  def logOperationComplete(
    activityLog: File,
    operation: String,
    trigger: String,
    startTime: DateTime,
    workflowId: Option[String] = None,
    recordCount: Option[Int] = None,
    metadata: Map[String, JsValue] = Map.empty
  ): Unit = {
    val now = DateTime.now()
    val durationSeconds = ((now.getMillis - startTime.getMillis) / 1000).toInt

    var entry = Json.obj(
      "timestamp" -> timeToString(now),
      "operation" -> operation,
      "status" -> "completed",
      "duration_seconds" -> durationSeconds
    )

    workflowId.foreach(id => entry = entry + ("workflow_id" -> JsString(id)))

    if (workflowId.isEmpty) {
      entry = entry + ("trigger" -> JsString(trigger))
    }

    recordCount.foreach(count => entry = entry + ("recordCount" -> JsNumber(count)))

    if (metadata.nonEmpty) {
      entry = entry + ("metadata" -> JsObject(metadata))
    }

    appendEntry(activityLog, entry)
  }

  /**
   * Log a failed operation.
   *
   * @param activityLog File to append to
   * @param operation Operation name
   * @param trigger "manual" or "automatic"
   * @param startTime When the operation started
   * @param errorMessage Description of the error
   * @param workflowId Optional workflow ID for automatic workflows
   * @param metadata Optional additional metadata
   */
  def logOperationFailed(
    activityLog: File,
    operation: String,
    trigger: String,
    startTime: DateTime,
    errorMessage: String,
    workflowId: Option[String] = None,
    metadata: Map[String, JsValue] = Map.empty
  ): Unit = {
    val now = DateTime.now()
    val durationSeconds = ((now.getMillis - startTime.getMillis) / 1000).toInt

    var entry = Json.obj(
      "timestamp" -> timeToString(now),
      "operation" -> operation,
      "status" -> "failed",
      "duration_seconds" -> durationSeconds,
      "errorMessage" -> errorMessage
    )

    workflowId.foreach(id => entry = entry + ("workflow_id" -> JsString(id)))

    if (workflowId.isEmpty) {
      entry = entry + ("trigger" -> JsString(trigger))
    }

    if (metadata.nonEmpty) {
      entry = entry + ("metadata" -> JsObject(metadata))
    }

    appendEntry(activityLog, entry)
  }

  /**
   * Complete a workflow (for automatic triggers).
   *
   * @param activityLog File to append to
   * @param workflowId Unique identifier for this workflow
   * @param startTime When the workflow started
   * @param totalRecords Total records processed in the workflow
   * @param metadata Optional additional metadata
   */
  def completeWorkflow(
    activityLog: File,
    workflowId: String,
    startTime: DateTime,
    totalRecords: Option[Int] = None,
    metadata: Map[String, JsValue] = Map.empty
  ): Unit = {
    val now = DateTime.now()
    val durationSeconds = ((now.getMillis - startTime.getMillis) / 1000).toInt

    var entry = Json.obj(
      "timestamp" -> timeToString(now),
      "workflow_id" -> workflowId,
      "trigger" -> "automatic",
      "status" -> "completed",
      "duration_seconds" -> durationSeconds
    )

    totalRecords.foreach(count => entry = entry + ("total_records" -> JsNumber(count)))

    if (metadata.nonEmpty) {
      entry = entry + ("metadata" -> JsObject(metadata))
    }

    appendEntry(activityLog, entry)
  }

  /**
   * Fail a workflow (for automatic triggers).
   *
   * @param activityLog File to append to
   * @param workflowId Unique identifier for this workflow
   * @param startTime When the workflow started
   * @param errorMessage Description of the error
   * @param failedOperation Which operation failed
   * @param metadata Optional additional metadata
   */
  def failWorkflow(
    activityLog: File,
    workflowId: String,
    startTime: DateTime,
    errorMessage: String,
    failedOperation: String,
    metadata: Map[String, JsValue] = Map.empty
  ): Unit = {
    val now = DateTime.now()
    val durationSeconds = ((now.getMillis - startTime.getMillis) / 1000).toInt

    var entry = Json.obj(
      "timestamp" -> timeToString(now),
      "workflow_id" -> workflowId,
      "trigger" -> "automatic",
      "status" -> "failed",
      "duration_seconds" -> durationSeconds,
      "errorMessage" -> errorMessage,
      "failed_operation" -> failedOperation
    )

    if (metadata.nonEmpty) {
      entry = entry + ("metadata" -> JsObject(metadata))
    }

    appendEntry(activityLog, entry)
  }

  /**
   * Generate a unique workflow ID based on spec and timestamp.
   */
  def generateWorkflowId(spec: String): String = {
    val timestamp = DateTime.now().getMillis
    s"wf_${spec}_${timestamp}"
  }

  /**
   * Append a JSON entry as a single line to the activity log.
   */
  private def appendEntry(activityLog: File, entry: JsObject): Unit = {
    val line = Json.stringify(entry) + "\n"
    val writer = appender(activityLog)
    try {
      writer.write(line)
      writer.flush()
    } finally {
      writer.close()
    }
  }
}
