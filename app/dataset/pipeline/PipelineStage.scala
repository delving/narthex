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

package dataset.pipeline

import dataset.DatasetContext
import organization.OrgContext
import services.ProgressReporter

/**
 * A pipeline stage as a synchronous unit of work (Phase A3c).
 *
 * Deliberately synchronous and free of Akka types in its signature: the
 * engine runs a stage on a worker thread and translates the result into
 * FSM events. This is the shape that transliterates to Go
 * (`Stage.Run(ctx) (StageResult, error)`) — no actor mailbox choreography,
 * no message classes, effects only through the passed context.
 *
 * Interruption arrives via the ProgressReporter's checkInterrupt (thrown
 * as an exception), identical to the actor-based stages.
 */
trait PipelineStage {

  /** Matches a PipelinePlan.STAGE_* id. */
  def id: String

  def run(ctx: StageContext): StageResult
}

case class StageContext(
  datasetContext: DatasetContext,
  orgContext: OrgContext,
  progressReporter: ProgressReporter
) {
  def spec: String = datasetContext.dsInfo.spec
}

sealed trait StageResult
/** Stage succeeded; `recordCount` meaning is stage-specific. */
case class StageOk(recordCount: Int) extends StageResult
/** Stage failed for a known reason (not an exception). */
case class StageFailed(message: String) extends StageResult
