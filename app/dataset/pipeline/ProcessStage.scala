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

import java.io.{StringReader, StringWriter}

import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success}

import org.apache.commons.io.FileUtils.deleteQuietly
import org.apache.jena.query.DatasetFactory
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import org.joda.time.LocalDateTime
import org.xml.sax.SAXException
import play.api.Logger
import play.api.libs.json.Json

import dataset.DatasetActor.Scheduled
import dataset.PipelinePlan
import dataset.SipFactory.SipGenerationFacts
import dataset.SipRepo.URIErrorsException
import eu.delving.groovy.DiscardRecordException
import record.PocketParser
import record.PocketParser.Pocket
import services.FileHandling
import services.FileHandling.{appender, createWriter, createZstdWriter}
import services.ProgressReporter.ProgressState
import services.RecordRegistry
import triplestore.GraphProperties.datasetType

/**
 * Map source pockets to RDF and write the processed output (Phase A3c-2:
 * extracted from the SourceProcessor actor — the body was already
 * synchronous; parallel mapping happens inside executeMappingsParallel).
 *
 * Full mode clears processed/ and parses the whole source repo; a delta
 * (scheduledOpt with modifiedAfter) preserves processed/ and parses only
 * the delta file. Joins the chain's open run or opens a standalone one;
 * stamps tombstones and seen-hashes into the registry (Hub3-state
 * semantics gated by registryEnabled; run bookkeeping unconditional).
 */
case class ProcessStage(scheduledOpt: Option[Scheduled]) extends PipelineStage {

  private val logger = Logger(getClass)

  val id: String = PipelinePlan.STAGE_PROCESS
  val progressState: ProgressState = ProgressState.PROCESSING

  def run(ctx: StageContext): StageResult = {
    val datasetContext = ctx.datasetContext
    val orgContext = ctx.orgContext
    val dsInfo = datasetContext.dsInfo
    val spec = ctx.spec

    val sourceFacts = datasetContext.sourceRepoOpt
      .map(_.sourceFacts)
      .getOrElse(return StageFailed(s"No source facts for $datasetContext"))
    val sipMapper = datasetContext.sipMapperOpt.getOrElse {
      val prefix = scala.util.Try(SipGenerationFacts(dsInfo).prefix).getOrElse("?")
      return StageFailed(
        s"No mapping available for $spec (prefix '$prefix'): the latest SIP has no mapping file. " +
          s"Open the SIP in SIP-Creator to create a mapping and upload it, or switch the dataset back to a prefix that has one.")
    }

    // Clear processed repo based on harvest type, not just presence of schedule
    val isIncrementalHarvest = scheduledOpt.exists(_.modifiedAfter.isDefined)
    if (isIncrementalHarvest) {
      logger.info("Incremental harvest - preserving existing processed data, will create new numbered file")
    } else {
      logger.info("Full harvest - clearing all previous processed data")
      datasetContext.processedRepo.clear()
    }

    val registryEnabled = orgContext.narthexConfig.registryEnabled
    val registry = orgContext.recordRegistry
    val registryKind =
      if (isIncrementalHarvest) RecordRegistry.KIND_INCREMENT
      else RecordRegistry.KIND_FULL
    // Join the chain's planned run if one is open; otherwise this is a
    // standalone processing command — open a run for it. Run bookkeeping is
    // unconditional pipeline infrastructure; registryEnabled gates only the
    // Hub3-state stamping below.
    val runIdOpt: Option[Long] = {
      val existing = registry.openRun(spec).map(_._1)
      val id = existing.getOrElse(registry.beginRun(spec, registryKind))
      logger.info(s"Registry run $id ${if (existing.isDefined) "continued" else "opened"} for $spec (kind=$registryKind)")
      registry.stageStarted(spec, id, "process", Some(Json.stringify(
        Json.obj("kind" -> registryKind, "file" -> scheduledOpt.map(_.file.getName)))))
      Some(id)
    }

    // Stamp OAI-PMH tombstones (from harvester's deleted.ids) into the
    // registry (records + tombstones table) so the next save phase can diff
    // them. deleted.ids holds raw OAI header ids; stampTombstones resolves
    // the variant matching existing rows, or drops would never match in Hub3.
    if (registryEnabled) runIdOpt.foreach { runId =>
      val rawIds = datasetContext.sourceRepoOpt.map(_.deletedIdSet.toSeq).getOrElse(Seq.empty)
      if (rawIds.nonEmpty) {
        val stamped = registry.stampTombstones(spec, rawIds, runId)
        logger.info(s"Registry: stamped ${stamped.size} tombstoned records ($spec)")
      }
    }

    val processedOutput = datasetContext.processedRepo.createOutput
    logger.info(s"Processing will write to: ${processedOutput.xmlFile.getName} (number: ${processedOutput.number})")
    val xmlOutput = createZstdWriter(processedOutput.xmlFile)
    val errorOutput = createWriter(processedOutput.errorFile)
    val bulkActionOutput = createWriter(processedOutput.bulkActionFile)
    val harvestingLogger = appender(datasetContext.harvestLogger)
    var validRecords = 0
    var invalidRecords = 0
    var time = System.currentTimeMillis()
    val dataset = DatasetFactory.createGeneral()

    def writeError(heading: String, error: String, id: String) = {
      invalidRecords += 1
      val errorString = s"""
                     |===== $invalidRecords: $heading === ( $id )=====
                     |$error
         """.stripMargin.trim
      if (!scheduledOpt.isEmpty) {
        harvestingLogger.write(errorString)
        harvestingLogger.newLine()
      }
      errorOutput.write(errorString)
      errorOutput.write("\n")
    }

    val batchSize = 100
    val batch = new ArrayBuffer[Pocket](batchSize)
    val seenBuf = new ArrayBuffer[(String, String)](batchSize)

    def processBatch(): Unit = {
      if (batch.nonEmpty) {
        ctx.progressReporter.checkInterrupt()
        val startAll = System.currentTimeMillis()

        // Execute mappings in parallel
        val results = sipMapper.executeMappingsParallel(batch.toSeq)
        val processing = System.currentTimeMillis() - startAll
        val startWrite = System.currentTimeMillis()

        // Write results sequentially (maintains order, thread-safe output)
        results.foreach { case (rawPocket, pocketTry) =>
          pocketTry match {
            case Success(pocket) =>
              val hash = PocketParser.sha1(pocket.text)
              pocket.writeTo(xmlOutput)
              validRecords += 1
              seenBuf += ((rawPocket.id, hash))

            case Failure(ue: URIErrorsException) =>
              writeError("URI ERRORS", ue.uriErrors.mkString("\n"), rawPocket.id)

            case Failure(disc: DiscardRecordException) =>
              writeError("DISCARDED RECORD", disc.getMessage, rawPocket.id)

            case Failure(sax: SAXException) =>
              writeError("XSD ERROR", sax.getMessage, rawPocket.id)

            case Failure(unexpected: Throwable) =>
              writeError("UNEXPECTED ERROR", unexpected.toString, rawPocket.id)
          }
        }

        batch.clear()
        if (seenBuf.nonEmpty) {
          // Hub3-state semantics stay behind the kill switch even though
          // run bookkeeping is unconditional.
          if (registryEnabled) {
            runIdOpt.foreach { runId =>
              registry.upsertSeenBatch(spec, seenBuf.toSeq, runId)
            }
          }
          seenBuf.clear()
        }

        val total = validRecords + invalidRecords
        if (total % 10000 == 0) {
          val now = System.currentTimeMillis()
          logger.debug(s"Processing $total: ${now - time}ms")
          time = now
        }
        val writing = System.currentTimeMillis() - startWrite
        val totalTime = System.currentTimeMillis() - startAll
        logger.debug(s"Batch total: ${totalTime}ms. Processing: ${processing}ms, writing: ${writing}ms")
      }
    }

    def catchPocket(rawPocket: Pocket): Unit = {
      batch += rawPocket
      if (batch.size >= batchSize) {
        processBatch()
      }
    }

    val idFilter = dsInfo.getIdFilter

    scheduledOpt.map { incremental =>
      logger.info(s"Processing incremental $sourceFacts")
      val (source, readProgress) = FileHandling.sourceFromFile(incremental.file)
      try {
        ctx.progressReporter.setReadProgress(readProgress)
        val parser = new PocketParser(sourceFacts, idFilter, orgContext)
        parser.parse(source, Set.empty[String], catchPocket, ctx.progressReporter)
        processBatch()
      } finally {
        source.close()
      }
    }.getOrElse {
      logger.info(s"Processing all $sourceFacts")
      datasetContext.sourceRepoOpt.foreach { sourceRepo =>
        sourceRepo.parsePockets(catchPocket, idFilter, ctx.progressReporter)
      }
    }

    // Process any remaining records in the final batch
    processBatch()

    xmlOutput.close()
    errorOutput.close()
    bulkActionOutput.close()
    if (invalidRecords == 0) deleteQuietly(processedOutput.errorFile)
    val scheduledOptOutput = if (!scheduledOpt.isEmpty) {
      Some(scheduledOpt.get.copy(file = processedOutput.xmlFile))
    } else {
      scheduledOpt
    }
    harvestingLogger.write(
      s"Processed Source Records - ${LocalDateTime.now()} - valid records: $validRecords - invalid records: $invalidRecords - scheduled: ${!scheduledOpt.isEmpty}")
    harvestingLogger.newLine()
    harvestingLogger.close()

    // Leave the registry run open so GraphSaver can stamp last_sent_run_id
    // on confirmed records and close it on save success.
    runIdOpt.foreach { runId =>
      registry.stageCompleted(spec, runId, "process", Some(Json.stringify(
        Json.obj("valid" -> validRecords, "invalid" -> invalidRecords))))
    }

    ProcessedRecords(validRecords, invalidRecords, scheduledOptOutput, runIdOpt)
  }
}
