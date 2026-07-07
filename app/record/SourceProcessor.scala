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

package record

import java.io.{File, FileOutputStream, StringReader, StringWriter}
import org.apache.jena.query.{Dataset, DatasetFactory}
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import akka.actor.{Actor, ActorLogging, Props}
import dataset.DatasetActor.{Scheduled, WorkFailure}
import dataset.DatasetContext
import dataset.SipFactory.SipGenerationFacts
import dataset.SipRepo.URIErrorsException
import eu.delving.groovy.DiscardRecordException
import organization.OrgContext
import nxutil.Utils.actorWork
import triplestore.GraphProperties.datasetType
import org.apache.commons.io.FileUtils.deleteQuietly
import org.joda.time.{DateTime, LocalDateTime}
import org.xml.sax.SAXException
import record.PocketParser.Pocket
import record.SourceProcessor._
import services.FileHandling._
import services.ProgressReporter.ProgressState._
import services.Temporal._
import services.{FileHandling, ProgressReporter}
import mapping.{DatasetMappingRepo, DefaultMappingRepo}
import play.api.libs.json.{JsValue, Json, Writes}
import services.RecordRegistry


import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object SourceProcessor {

  case class AdoptSource(file: File, orgContext: OrgContext)

  case class SourceAdoptionComplete(file: File)

  case object GenerateSipZip

  case class SipZipGenerationComplete(recordCount: Int)

  case class Process(scheduledOpt: Option[Scheduled])

  case class ProcessingComplete(validRecords: Int,
                                invalidRecords: Int,
                                scheduledOpt: Option[Scheduled],
                                registryRunId: Option[Long] = None)

  def props(datasetContext: DatasetContext, orgContext: OrgContext) =
    Props(new SourceProcessor(datasetContext, orgContext))

}

class SourceProcessor(val datasetContext: DatasetContext,
                      orgContext: OrgContext)
    extends Actor
    with ActorLogging {

  var progress: Option[ProgressReporter] = None
  val dsInfo = datasetContext.dsInfo

  def receive = {

    case AdoptSource(file, orgContext) =>
      actorWork(context) {
        log.info(s"Adopt source ${file.getAbsolutePath}")
        datasetContext.sourceRepoOpt match {
          case Some(sourceRepo) =>
            val progressReporter = ProgressReporter(ADOPTING, context.parent)
            progress = Some(progressReporter)
            sourceRepo.acceptFile(file, progressReporter) match {
              case Some(adoptedFile) =>
                context.parent ! SourceAdoptionComplete(adoptedFile)
              case None =>
                context.parent ! WorkFailure(
                  s"File not accepted: ${file.getAbsolutePath}")
            }
          case None =>
            context.parent ! WorkFailure("Missing source repository!")
        }
      }

    // GenerateSipZip moved to dataset.pipeline.GenerateSipStage (Phase A3c-1)

    case Process(scheduledOpt) =>
      actorWork(context) {
        val sourceFacts = datasetContext.sourceRepoOpt
          .map(_.sourceFacts)
          .getOrElse(
            throw new RuntimeException(s"No source facts for $datasetContext"))
        val sipMapper = datasetContext.sipMapperOpt.getOrElse(
          throw new RuntimeException(s"No sip mapper for $datasetContext"))

        // CRITICAL FIX: Clear processed repo based on harvest type, not just presence of schedule
        val isIncrementalHarvest = scheduledOpt.exists(_.modifiedAfter.isDefined)
        if (isIncrementalHarvest) {
          log.info("Incremental harvest - preserving existing processed data, will create new numbered file")
        } else {
          log.info("Full harvest - clearing all previous processed data")
          datasetContext.processedRepo.clear()
        }

        val registryEnabled = orgContext.narthexConfig.registryEnabled
        val registry = orgContext.recordRegistry
        val spec = dsInfo.spec
        val registryKind =
          if (isIncrementalHarvest) RecordRegistry.KIND_INCREMENT
          else RecordRegistry.KIND_FULL
        // Join the chain's planned run if one is open; otherwise this is a
        // standalone processing command — open a run for it. Run bookkeeping
        // is unconditional pipeline infrastructure; registryEnabled gates
        // only the Hub3-state stamping below.
        val runIdOpt: Option[Long] = {
          val existing = registry.openRun(spec).map(_._1)
          val id = existing.getOrElse(registry.beginRun(spec, registryKind))
          log.info(s"Registry run $id ${if (existing.isDefined) "continued" else "opened"} for $spec (kind=$registryKind)")
          registry.stageStarted(spec, id, "process", Some(play.api.libs.json.Json.stringify(
            play.api.libs.json.Json.obj(
              "kind" -> registryKind,
              "file" -> scheduledOpt.map(_.file.getName)
            ))))
          Some(id)
        }

        // Stamp OAI-PMH tombstones (from harvester's deleted.ids) into the
        // registry (records + tombstones table) so the next save phase can
        // diff them. Does not filter pockets — SourceRepo still uses the
        // file for that. deleted.ids holds raw OAI header identifiers; the
        // registry keys on pocket ids — stampTombstones resolves the variant
        // matching existing rows, or drops would never match in Hub3 either.
        if (registryEnabled) runIdOpt.foreach { runId =>
          val rawIds = datasetContext.sourceRepoOpt.map(_.deletedIdSet.toSeq).getOrElse(Seq.empty)
          if (rawIds.nonEmpty) {
            val stamped = registry.stampTombstones(spec, rawIds, runId)
            log.info(s"Registry: stamped ${stamped.size} tombstoned records ($spec)")
          }
        }

        val processedOutput = datasetContext.processedRepo.createOutput
        log.info(s"Processing will write to: ${processedOutput.xmlFile.getName} (number: ${processedOutput.number})")
        val xmlOutput = createZstdWriter(processedOutput.xmlFile)  // ZSTD compressed output
        val errorOutput = createWriter(processedOutput.errorFile)
        val bulkActionOutput = createWriter(processedOutput.bulkActionFile)
        //val nquadOutput = createWriter(processedOutput.nquadFile)
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

        def writeBulkAction(rdf: String, graphUri: String, localId: String) = {
          val model = dataset.getNamedModel(graphUri)
          try {
            model.read(new StringReader(rdf), null, "RDF/XML")
          } catch {
            case e: Throwable =>
              errorOutput.write(rdf)
              errorOutput.write("\n")
              throw e
          }
          //val model = dataset.getNamedModel(graphUri)
          val triples = new StringWriter()
          RDFDataMgr.write(triples, model, RDFFormat.JSONLD_FLAT)

          val orgId = dsInfo.orgId
          val spec = dsInfo.spec
          val hubId = s"${orgId}_${spec}_$localId"
          //val localHash = model.listObjectsOfProperty(model.getProperty(contentHash.uri)).toList().head.toString
          val actionMap = Json.obj(
            "hubId" -> hubId,
            "orgId" -> orgId,
            "localId" -> localId,
            "dataset" -> spec,
            "graphUri" -> graphUri,
            "type" -> dsInfo.getLiteralProp(datasetType).getOrElse("narthex_record").toString(),
            "action" -> "index",
            "graphMimeType" -> "application/ld+json",
            //"contentHash" -> localHash.toString,
            "graph" -> s"$triples".stripMargin.trim
          )
          bulkActionOutput.write(actionMap.toString())
          bulkActionOutput.write("\n")
          // TODO make sure the named graph is part of the model
          //var nquads = new StringWriter()
          //RDFDataMgr.write(nquads, model, RDFFormat.NQUADS)
          //nquadOutput.write(s"$nquads".stripMargin.trim)
          //nquadOutput.write("\n")
        }

        // Batch size for parallel processing
        val batchSize = 100
        val batch = new ArrayBuffer[Pocket](batchSize)
        val seenBuf = new ArrayBuffer[(String, String)](batchSize)

        def processBatch(): Unit = {
          if (batch.nonEmpty) {
            progress.get.checkInterrupt()
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
              // run bookkeeping is now unconditional.
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
              log.debug(s"Processing $total: ${now - time}ms")
              time = now
            }
            val writing = System.currentTimeMillis() - startWrite
            val totalTime = System.currentTimeMillis() - startAll
            log.debug(s"Batch total: ${totalTime}ms. Processing: ${processing}ms, writing: ${writing}ms")
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
          log.info(s"Processing incremental $sourceFacts")
          val (source, readProgress) =
            FileHandling.sourceFromFile(incremental.file)
          try {

            val progressReporter = ProgressReporter(PROCESSING, context.parent)
            progressReporter.setReadProgress(readProgress)
            progress = Some(progressReporter)
            val parser = new PocketParser(sourceFacts, idFilter, orgContext)
            parser.parse(source,
                         Set.empty[String],
                         catchPocket,
                         progressReporter)
            // Process any remaining records in the final batch
            processBatch()
          } finally {
            source.close()
          }
        }.getOrElse {
          log.info(s"Processing all $sourceFacts")
          datasetContext.sourceRepoOpt.foreach { sourceRepo =>
            val progressReporter = ProgressReporter(PROCESSING, context.parent)
            progress = Some(progressReporter)
            sourceRepo.parsePockets(catchPocket, idFilter, progressReporter)
          }
        }

        // Process any remaining records in the final batch
        processBatch()

        xmlOutput.close()
        errorOutput.close()
        bulkActionOutput.close()
        //nquadOutput.close()
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

        // Leave the registry run open so GraphSaver can stamp
        // last_sent_run_id on confirmed records and close it on save success.
        runIdOpt.foreach { runId =>
          registry.stageCompleted(spec, runId, "process", Some(play.api.libs.json.Json.stringify(
            play.api.libs.json.Json.obj("valid" -> validRecords, "invalid" -> invalidRecords))))
        }
        context.parent ! ProcessingComplete(validRecords,
                                            invalidRecords,
                                            scheduledOptOutput,
                                            runIdOpt)
      }
  }

}
