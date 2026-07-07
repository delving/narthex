//===========================================================================
//    Copyright 2026 Delving B.V.
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

package dataset

import play.api.libs.json._

import dataset.DatasetStatusProjector.ProjectedStatus
import organization.OrgContext

/**
 * Phase C1: the dataset status document — phase, current run, and the
 * available actions, all computed backend-side. The UI renders these and
 * decides nothing (the affordance free-for-all of ~20 ng-show conditionals
 * ends here). Served additively on the light list JSON, the websocket idle
 * message, and the dataset info endpoint.
 *
 * `actions` is a PURE function of (projected artifacts, phase, config
 * facts) — the single place that answers "what can the user do next".
 * Vocabulary matches the planner's world; the UI maps each action to the
 * existing command it always sent.
 */
object DatasetStatusDoc {

  // Facts the affordance function needs beyond the projected artifacts.
  case class Facts(
    delimitersSet: Option[String],
    errorMessage: Option[String],
    inRetry: Boolean
  )

  val PHASE_IDLE = "idle"
  val PHASE_QUEUED = "queued"
  val PHASE_RUNNING = "running"
  val PHASE_RETRY = "retry"
  val PHASE_ERROR = "error"
  val PHASE_DISABLED = "disabled"

  /** Pure: which workflow actions are valid right now. */
  def actions(p: ProjectedStatus, phase: String, facts: Facts): Seq[String] = phase match {
    case PHASE_RUNNING | PHASE_QUEUED => Seq("cancel")
    case PHASE_DISABLED => Seq("enable")
    case _ =>
      // Delimiters (record root / unique id) are valid when the dataset has
      // progressed past them, or they were set after the latest raw analysis.
      val delimitersValid = p.sourced.isDefined || facts.delimitersSet.exists { set =>
        p.rawAnalyzed.forall(ra => services.Temporal.stringToTime(set).isAfter(ra))
      }
      val b = Seq.newBuilder[String]
      if (p.raw.isDefined) b += "analyze_raw"
      if (p.raw.isDefined && p.rawAnalyzed.isDefined && !delimitersValid) b += "delimit"
      if (delimitersValid && p.sourced.isEmpty) b += "first_harvest"
      if (p.sourced.isDefined) { b += "analyze_source"; b += "generate_sip"; b += "fast_save" }
      if (p.sourced.isDefined && p.processable.isDefined) b += "process"
      if (p.processed.isDefined) b += "analyze_processed"
      if (p.analyzed.isDefined) b += "save"
      b += "disable"
      b.result()
  }

  /** Phase from queue lease + open run + stored flags. */
  def phase(orgContext: OrgContext, spec: String, p: ProjectedStatus, facts: Facts): String = {
    if (p.disabled.isDefined) PHASE_DISABLED
    else if (orgContext.jobQueue.isLeased(spec) || orgContext.recordRegistry.openRun(spec).isDefined) PHASE_RUNNING
    else if (orgContext.jobQueue.queued().exists(_.spec == spec)) PHASE_QUEUED
    else if (facts.inRetry) PHASE_RETRY
    else if (facts.errorMessage.exists(_.nonEmpty)) PHASE_ERROR
    else PHASE_IDLE
  }

  /** The open run with its stage trail, if any. */
  def runJson(orgContext: OrgContext, spec: String): Option[JsObject] =
    orgContext.recordRegistry.openRun(spec).map { case (runId, planJsonOpt) =>
      val stages = orgContext.recordRegistry.runStages(spec, runId)
      val current = stages.find(_._2 == "running").map(_._1)
      Json.obj(
        "id" -> runId,
        "kind" -> planJsonOpt.flatMap(PipelinePlan.Plan.fromJson).map(_.kind),
        "stage" -> current,
        "stages" -> stages.map { case (id, st) => Json.obj("id" -> id, "status" -> st) }
      )
    }

  /** The newest artifact — what happened last (the badge). */
  def lastStep(p: ProjectedStatus): Option[String] =
    Seq(
      "raw" -> p.raw, "raw_analyzed" -> p.rawAnalyzed, "sourced" -> p.sourced,
      "source_analyzed" -> p.sourceAnalyzed, "sip" -> p.mappable,
      "mapping" -> p.processable, "processed" -> p.processed,
      "analyzed" -> p.analyzed, "saved" -> p.saved, "incremental_saved" -> p.incrementalSaved
    ).collect { case (name, Some(dt)) => name -> dt.getMillis }
      .sortBy(_._2).lastOption.map(_._1)

  /** Additive JSON fields for the list / websocket / info payloads. */
  def fields(orgContext: OrgContext, spec: String, p: ProjectedStatus, facts: Facts): Seq[(String, JsValue)] = {
    val ph = phase(orgContext, spec, p, facts)
    Seq(
      "phase" -> JsString(ph),
      "actions" -> Json.toJson(actions(p, ph, facts)),
      "lastStep" -> lastStep(p).map(JsString(_)).getOrElse[JsValue](JsNull),
      "run" -> runJson(orgContext, spec).getOrElse[JsValue](JsNull)
    )
  }
}
