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
    inRetry: Boolean,
    errorTime: Option[String] = None
  )

  val PHASE_IDLE = "idle"
  val PHASE_QUEUED = "queued"
  val PHASE_RUNNING = "running"
  val PHASE_RETRY = "retry"
  val PHASE_ERROR = "error"
  val PHASE_DISABLED = "disabled"

  // Pipeline rank of the artifact each action CONSUMES — an action hides
  // while a run is producing (replacing) that artifact or anything before
  // it. Earlier/redo actions stay visible (the linear model: from analyzed
  // you can still re-run make-sip).
  private val actionDependsRank = Map(
    "analyze_raw" -> 0, "delimit" -> 1, "first_harvest" -> 1,
    "analyze_source" -> 2, "generate_sip" -> 2, "fast_save" -> 2,
    "process" -> 4, "analyze_processed" -> 6, "save" -> 7, "disable" -> 0)
  private val stageOutputRank = Map(
    "harvest" -> 2, "generate_sip" -> 4, "process" -> 6,
    "analyze" -> 7, "save" -> 8, "reconcile" -> 8)

  /** Pure: which workflow actions are valid right now. */
  def actions(p: ProjectedStatus, phase: String, facts: Facts, runningStage: Option[String] = None): Seq[String] = phase match {
    case PHASE_RUNNING | PHASE_QUEUED =>
      // Linear model: upstream actions remain (they re-run earlier steps);
      // only actions depending on what this run is producing hide. A queued
      // job hasn't started mutating anything yet — everything stays.
      val outputRank = runningStage.flatMap(stageOutputRank.get).getOrElse(Int.MaxValue)
      val base = workflowActions(p, facts).filter(a => actionDependsRank.getOrElse(a, 0) < outputRank)
      "cancel" +: base
    case PHASE_DISABLED => Seq("enable")
    case _ => workflowActions(p, facts)
  }

  private def workflowActions(p: ProjectedStatus, facts: Facts): Seq[String] = {
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

  /**
   * Phase from queue lease + open run + run outcomes + stored flags.
   * Phase C3: an error IS a failed latest run — one later successful run
   * clears it. The errorMessage prop remains only as a legacy fallback for
   * datasets with no registry runs.
   */
  def phase(orgContext: OrgContext, spec: String, p: ProjectedStatus, facts: Facts): String = {
    lazy val latestOutcome = orgContext.recordRegistry.latestRunOutcome(spec)
    if (p.disabled.isDefined) PHASE_DISABLED
    else if (orgContext.jobQueue.isLeased(spec) || orgContext.recordRegistry.openRun(spec).isDefined) PHASE_RUNNING
    else if (orgContext.jobQueue.queued().exists(_.spec == spec)) PHASE_QUEUED
    else if (facts.inRetry) PHASE_RETRY
    else if (latestOutcome.exists(_.status == "failed")) PHASE_ERROR
    else if (propErrorCurrent(latestOutcome, facts)) PHASE_ERROR
    else PHASE_IDLE
  }

  /**
   * A stored error prop counts when there are no runs at all (legacy), or
   * when it is NEWER than the latest run's completion — a manual save that
   * adopts an already-completed run can only leave its failure in the prop
   * (goes away when save becomes a first-class run, A3c-3).
   */
  private def propErrorCurrent(latestOutcome: Option[services.RecordRegistry.RunOutcome], facts: Facts): Boolean =
    facts.errorMessage.exists(_.nonEmpty) && {
      latestOutcome match {
        case None => true
        case Some(o) =>
          val runMillis = o.completedAt.orElse(Some(o.startedAt)).map(t => services.Temporal.stringToTime(t).getMillis)
          facts.errorTime.exists(t => runMillis.forall(services.Temporal.stringToTime(t).getMillis > _))
      }
    }

  /** Error detail for phase=error: the failed run's stage error or note. */
  def errorJson(orgContext: OrgContext, spec: String, facts: Facts): Option[JsObject] = {
    val latest = orgContext.recordRegistry.latestRunOutcome(spec)
    latest match {
      case Some(o) if o.status == "failed" =>
        Some(Json.obj(
          "runId" -> o.runId,
          "stage" -> o.failedStage,
          "message" -> o.failedError.orElse(o.note).getOrElse[String]("run failed"),
          "at" -> o.completedAt
        ))
      case other if propErrorCurrent(other, facts) =>
        facts.errorMessage.filter(_.nonEmpty).map(m => Json.obj("message" -> m, "at" -> facts.errorTime))
      case _ => None
    }
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

  /**
   * The counts document (Phase D3, decisions 2026-07-10): truth-only —
   * registry records/runs/stages + source-dir files. Totals from
   * full-truth sources; increments always deltas. `indexed` is the
   * registry side (sent/pending); the authoritative Hub3 number lives on
   * the index-stats page (no cheap per-list Hub3 call exists).
   */
  def countsJson(orgContext: OrgContext, spec: String): JsObject = {
    val reg = orgContext.recordRegistry
    val sourceDir = new java.io.File(new java.io.File(orgContext.datasetsDir, spec), "source")

    def actSum: Option[Int] = Option(sourceDir.listFiles())
      .map(_.filter(_.getName.endsWith(".act"))
        .flatMap(f => scala.util.Try(
          org.apache.commons.io.FileUtils.readFileToString(f, "UTF-8").trim.toInt).toOption).sum)
      .filter(_ > 0)
    def deletedIdsCount: Option[Int] = {
      val f = new java.io.File(sourceDir, "deleted.ids")
      if (f.exists())
        scala.util.Try(org.apache.commons.io.FileUtils.readLines(f, "UTF-8").size).toOption
      else None
    }

    val seen = reg.countIfExists(spec, "seen").filter(_ > 0)
    val acquiredRecords = seen.orElse(actSum)
    val deleted = reg.countIfExists(spec, "deleted").filter(_ > 0).orElse(deletedIdsCount)
    val methodRaw = orgContext.datasetsDb.getProp(spec, "acquisitionMethod")
    val method = methodRaw.map {
      case "upload" => "upload"
      case _ => "harvest" // pmh/adlib/json/harvest all mean harvested
    }

    val processed = reg.latestProcessOutput(spec).map { case (runId, at, outputJson) =>
      val js = scala.util.Try(Json.parse(outputJson)).toOption
      Json.obj(
        "valid" -> js.flatMap(j => (j \ "valid").asOpt[Int]),
        "invalid" -> js.flatMap(j => (j \ "invalid").asOpt[Int]),
        "runId" -> runId,
        "at" -> at
      )
    }

    val lastIncrement = reg.listRuns(spec, 3650)
      .filter(r => r.kind == "incremental" && r.status == "completed")
      .sortBy(_.runId).lastOption.map { r =>
        Json.obj("added" -> r.added, "changed" -> r.changed, "deleted" -> r.deleted,
          "sent" -> r.sent, "runId" -> r.runId, "at" -> r.completedAt)
      }

    val pending = reg.pendingCounts(spec)
    val indexed = Json.obj(
      "sent" -> seen.map(s => math.max(0, s - pending.pendingIndex)),
      "pendingIndex" -> pending.pendingIndex,
      "pendingDrops" -> pending.pendingDrops
    )

    Json.obj(
      "acquired" -> Json.obj("records" -> acquiredRecords, "deleted" -> deleted, "method" -> method),
      "processed" -> processed,
      "lastIncrement" -> lastIncrement,
      "indexed" -> indexed
    )
  }

  /** Additive JSON fields for the list / websocket / info payloads. */
  def fields(orgContext: OrgContext, spec: String, p: ProjectedStatus, facts: Facts): Seq[(String, JsValue)] = {
    val ph = phase(orgContext, spec, p, facts)
    val runJs = runJson(orgContext, spec)
    val runningStage = runJs.flatMap(js => (js \ "stage").asOpt[String])
    val queuePosition: JsValue =
      if (ph == PHASE_QUEUED)
        JsNumber(orgContext.jobQueue.queued().indexWhere(_.spec == spec) + 1)
      else JsNull
    Seq(
      "phase" -> JsString(ph),
      "actions" -> Json.toJson(actions(p, ph, facts, runningStage)),
      "lastStep" -> lastStep(p).map(JsString(_)).getOrElse[JsValue](JsNull),
      "run" -> runJs.getOrElse[JsValue](JsNull),
      "queuePosition" -> queuePosition,
      "counts" -> countsJson(orgContext, spec),
      "error" -> (if (ph == PHASE_ERROR) errorJson(orgContext, spec, facts).getOrElse[JsValue](JsNull) else JsNull)
    )
  }
}
