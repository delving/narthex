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

import java.io.File

import org.joda.time.DateTime
import play.api.libs.json.{JsArray, JsString, Json}

import dataset.DatasetActor.Scheduled
import services.RecordRegistry

/**
 * The pure planner (Phase A2 of the ingestion pipeline redesign).
 *
 * Every multi-stage chain is decided HERE, up front, as an ordered stage
 * list persisted on the run row — replacing the mutable actor flags
 * (fastSaveScheduledOpt, fastSaveAfterProcessing, fastProcessOnly,
 * autoProcessAfterFirstHarvest) that encoded continuations in volatile
 * memory and were set in one handler and consumed (or silently dropped)
 * in another.
 *
 * Scope note: the planner covers post-harvest continuation chains. The
 * harvest stage itself joins the run in Phase A3; manual save-only runs
 * (with source_run_id lineage) land in Phase A4 — until then manual saves
 * keep the run-adoption mechanism.
 */
object PipelinePlan {

  val STAGE_HARVEST      = "harvest"
  val STAGE_GENERATE_SIP = "generate_sip"
  val STAGE_PROCESS      = "process"
  val STAGE_SAVE         = "save"
  val STAGE_RECONCILE    = "reconcile"
  val STAGE_ANALYZE      = "analyze"

  /**
   * How a save treats Hub3 — decided once by the planner (or the save
   * dispatcher), never re-derived from interacting booleans. The revision
   * sweep (clear_orphans) and delta filtering are mutually exclusive by
   * construction: filtering a swept save would mass-delete every unchanged
   * record from Hub3.
   */
  sealed abstract class SaveMode(val name: String)
  case object FullSendWithSweep      extends SaveMode("full_send_with_sweep")
  case object DeltaSendRegistryOwned extends SaveMode("delta_send_registry_owned")
  case object IncrementalFileSend    extends SaveMode("incremental_file_send")

  object SaveMode {
    def fromName(name: String): Option[SaveMode] = name match {
      case FullSendWithSweep.name      => Some(FullSendWithSweep)
      case DeltaSendRegistryOwned.name => Some(DeltaSendRegistryOwned)
      case IncrementalFileSend.name    => Some(IncrementalFileSend)
      case _                           => None
    }
  }

  /** The single decision function for the save mode. */
  def saveModeFor(incremental: Boolean, registryEnabled: Boolean, keepRevisionSweep: Boolean): SaveMode =
    if (incremental) IncrementalFileSend
    else if (!registryEnabled || keepRevisionSweep) FullSendWithSweep
    else DeltaSendRegistryOwned

  /**
   * A planned chain: ordered stages plus the facts later stages need,
   * serialized onto the run row so a restart (or a reviewer) can always
   * answer "what was this run going to do".
   */
  case class Plan(
    stages: List[String],
    kind: String,                    // RecordRegistry.KIND_FULL | KIND_INCREMENT
    incremental: Boolean,
    deltaFilePath: Option[String],   // source delta zip for incremental processing
    modifiedAfterIso: Option[String]
  ) {
    def includes(stage: String): Boolean = stages.contains(stage)

    def nextAfter(stage: String): Option[String] = {
      val idx = stages.indexOf(stage)
      if (idx >= 0 && idx + 1 < stages.length) Some(stages(idx + 1)) else None
    }

    /** Rebuild the Scheduled for the processing stage, if this is a delta chain. */
    def scheduledForProcessing: Option[Scheduled] =
      if (incremental)
        deltaFilePath.map(path => Scheduled(modifiedAfterIso.map(DateTime.parse), new File(path)))
      else None

    def toJson: String = Json.stringify(Json.obj(
      "stages" -> JsArray(stages.map(JsString)),
      "kind" -> kind,
      "incremental" -> incremental,
      "deltaFile" -> deltaFilePath,
      "modifiedAfter" -> modifiedAfterIso
    ))
  }

  object Plan {
    def fromJson(json: String): Option[Plan] =
      scala.util.Try {
        val js = Json.parse(json)
        Plan(
          stages = (js \ "stages").as[List[String]],
          kind = (js \ "kind").as[String],
          incremental = (js \ "incremental").as[Boolean],
          deltaFilePath = (js \ "deltaFile").asOpt[String],
          modifiedAfterIso = (js \ "modifiedAfter").asOpt[String]
        )
      }.toOption
  }

  private def fullChain(stages: List[String]) = Plan(
    stages = stages,
    kind = RecordRegistry.KIND_FULL,
    incremental = false,
    deltaFilePath = None,
    modifiedAfterIso = None
  )

  /**
   * Continuation after a harvest delivered a source file.
   * - true incremental (modifiedAfter set): process ONLY the delta file,
   *   save only its output — the chain bug-061 silently degraded to full.
   * - FromScratchIncremental / FromScratch (mod empty): full reprocess.
   * - no mapper: the chain ends after SIP generation (dataset is MAPPABLE
   *   but not PROCESSABLE).
   */
  def afterHarvest(hasMapper: Boolean, modifiedAfter: Option[DateTime], file: File): Plan = {
    if (!hasMapper) fullChain(List(STAGE_GENERATE_SIP))
    else modifiedAfter match {
      case Some(mod) => Plan(
        stages = List(STAGE_GENERATE_SIP, STAGE_PROCESS, STAGE_SAVE, STAGE_RECONCILE),
        kind = RecordRegistry.KIND_INCREMENT,
        incremental = true,
        deltaFilePath = Some(file.getAbsolutePath),
        modifiedAfterIso = Some(mod.toString)
      )
      case None => fullChain(List(STAGE_GENERATE_SIP, STAGE_PROCESS, STAGE_SAVE, STAGE_RECONCILE))
    }
  }

  /** First harvest with auto-process (discovery imports): stop at PROCESSED, no save. */
  def afterFirstHarvestAutoProcess(hasMapper: Boolean): Plan =
    if (hasMapper) fullChain(List(STAGE_GENERATE_SIP, STAGE_PROCESS))
    else fullChain(List(STAGE_GENERATE_SIP))

  /** Manual fast save from SOURCED: full SIP + process + save chain. */
  def fastSaveFromSourced: Plan =
    fullChain(List(STAGE_GENERATE_SIP, STAGE_PROCESS, STAGE_SAVE, STAGE_RECONCILE))

  /** Manual fast save from PROCESSABLE: process + save (no SIP regeneration). */
  def fastSaveFromProcessable: Plan =
    fullChain(List(STAGE_PROCESS, STAGE_SAVE, STAGE_RECONCILE))

  /** Manual fast process: full chain that stops at PROCESSED. */
  def fastProcess: Plan =
    fullChain(List(STAGE_GENERATE_SIP, STAGE_PROCESS))

  /** Bare manual "start processing". */
  def processOnly: Plan =
    fullChain(List(STAGE_PROCESS))

  /** Source adoption (upload): regenerate the SIP, then stop. */
  def afterAdoption: Plan =
    fullChain(List(STAGE_GENERATE_SIP))

  /** Standalone manual "make sip" (Phase C2: every action is a run). */
  def generateSipOnly: Plan =
    fullChain(List(STAGE_GENERATE_SIP))

  /** Analysis of raw / source / processed data (Phase C2). */
  def analyzeOnly: Plan =
    fullChain(List(STAGE_ANALYZE))

  /**
   * A harvest-initiated run starts with only [harvest] — the continuation
   * depends on the harvest outcome and is REPLANNED at HarvestComplete
   * (harvest-prefixed continuation, deletes-only reconcile, or discarded
   * entirely for a no-op noRecordsMatch tick).
   */
  def forHarvest(incremental: Boolean): Plan = Plan(
    stages = List(STAGE_HARVEST),
    kind = if (incremental) RecordRegistry.KIND_INCREMENT else RecordRegistry.KIND_FULL,
    incremental = incremental,
    deltaFilePath = None,
    modifiedAfterIso = None
  )

  /** Replan: prefix the decided continuation with the completed harvest stage. */
  def harvestThen(continuation: Plan): Plan =
    continuation.copy(stages = STAGE_HARVEST :: continuation.stages)

  /** Replan for a deletes-only delta: tombstone sync without the pipeline. */
  def harvestDeletesOnly: Plan = Plan(
    stages = List(STAGE_HARVEST, STAGE_RECONCILE),
    kind = RecordRegistry.KIND_INCREMENT,
    incremental = true,
    deltaFilePath = None,
    modifiedAfterIso = None
  )
}
