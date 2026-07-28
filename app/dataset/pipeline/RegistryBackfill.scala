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

import scala.jdk.CollectionConverters._

import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.Json

import dataset.DatasetContext
import organization.OrgContext
import record.PocketParser
import services.ProgressReporter

/**
 * Registers (localId, contentHash) rows from the PROCESSED output into the
 * record registry — for externally-processed datasets (SIP-Creator's
 * upload-processed endpoint), where the process step ran outside Narthex
 * and nothing ever stamped the registry. Without this, a registry-owned
 * save finds no pending records and silently sends nothing (observed: mip,
 * 26k records, indexed zero).
 *
 * The hash basis is the canonical N-TRIPLES serialization of each record's
 * graph (sorted lines) — different from ProcessStage's pocket-text hash,
 * but externally-processed datasets only ever register through THIS path,
 * so the basis stays consistent per dataset.
 */
object RegistryBackfill {

  private val logger = Logger(getClass)

  def fromProcessedOutput(datasetContext: DatasetContext, orgContext: OrgContext): Int = {
    val spec = datasetContext.dsInfo.spec
    val registry = orgContext.recordRegistry
    val runId = registry.beginRun(spec, services.RecordRegistry.KIND_FULL, trigger = Some("upload-processed"))
    registry.stageStarted(spec, runId, "process", Some(Json.stringify(Json.obj("external" -> true))))

    var count = 0
    val buf = scala.collection.mutable.Buffer.empty[(String, String)]
    val reader = datasetContext.processedRepo.createGraphReaderXML(None, new DateTime(), ProgressReporter())

    try {
      var chunkOpt = reader.readChunkOpt
      while (chunkOpt.isDefined) {
        val chunk = chunkOpt.get
        chunk.dataset.listNames().asScala.foreach { graphUri =>
          scala.util.Try(chunk.dsInfo.extractSpecIdFromGraphName(graphUri)).toOption.foreach { case (_, localId) =>
            val sw = new java.io.StringWriter()
            chunk.dataset.getNamedModel(graphUri).write(sw, "N-TRIPLE")
            val hash = PocketParser.sha1(sw.toString.linesIterator.toSeq.sorted.mkString("\n"))
            buf += ((localId, hash))
            count += 1
            if (buf.size >= 500) {
              registry.upsertSeenBatch(spec, buf.toSeq, runId)
              buf.clear()
            }
          }
        }
        chunkOpt = reader.readChunkOpt
      }
      if (buf.nonEmpty) registry.upsertSeenBatch(spec, buf.toSeq, runId)

      registry.stageCompleted(spec, runId, "process", Some(Json.stringify(
        Json.obj("valid" -> count, "invalid" -> 0, "external" -> true))))
      registry.completeRun(spec, runId)
      logger.info(s"Registry backfill for $spec: registered $count externally-processed record(s) (run $runId)")
      count
    } catch {
      case ex: Exception =>
        scala.util.Try(registry.failOpenRuns(spec, s"registry backfill failed: ${ex.getMessage}"))
        throw ex
    } finally {
      scala.util.Try(reader.close())
    }
  }
}
