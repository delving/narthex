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

import java.io.{File, FileOutputStream}

import org.apache.commons.io.FileUtils.deleteQuietly
import play.api.Logger

import dataset.PipelinePlan
import dataset.SipFactory.SipGenerationFacts
import mapping.DefaultMappingRepo
import services.ProgressReporter.ProgressState

/**
 * Generate the SIP zip + pockets file from the full accumulated source
 * (Phase A3c-1: first stage extracted from the SourceProcessor actor —
 * the body was already synchronous; only the actor plumbing is gone).
 *
 * Inputs: source repo (all zips), effective mapping, prior SIP for reuse.
 * Outputs: fresh SIP zip in sips/, regenerated pocket file.
 * Idempotent: pure derivation of its inputs; safe to re-run.
 */
object GenerateSipStage extends PipelineStage {

  private val logger = Logger(getClass)

  val id: String = PipelinePlan.STAGE_GENERATE_SIP
  val progressState: ProgressState = ProgressState.GENERATING

  def run(ctx: StageContext): StageResult = {
    val datasetContext = ctx.datasetContext
    val dsInfo = datasetContext.dsInfo
    val spec = ctx.spec

    datasetContext.sourceRepoOpt match {
      case None => StageFailed("No data for generating SipZip")
      case Some(sourceRepo) =>
        val pocketFile = datasetContext.pocketFile
        pocketFile.getParentFile.mkdirs()
        val idFilter = dsInfo.getIdFilter
        // Pocket generation streams the whole accumulated source — skip it
        // when the manifest proves the existing pockets match the current
        // inputs (plan §2.6). The SIP zip below is still rebuilt every time
        // (the mapping may have changed).
        val manifestFile = PocketManifest.manifestFileFor(pocketFile)
        val currentInputs = PocketManifest.inputs(datasetContext.sourceDir, idFilter)
        val pocketCount = PocketManifest.cachedCount(manifestFile, currentInputs, pocketFile) match {
          case Some(count) =>
            logger.info(s"Dataset $spec: pockets unchanged (manifest match) — skipping regeneration ($count records)")
            count
          case None =>
            val pocketOutput = new FileOutputStream(pocketFile)
            val count =
              try sourceRepo.generatePockets(pocketOutput, idFilter, ctx.progressReporter)
              finally pocketOutput.close()
            PocketManifest.write(manifestFile, currentInputs, count, pocketFile)
            count
        }

        val recDefVersionHashOpt = dsInfo.getRecDefVersionHash
        val targetPrefix = SipGenerationFacts(dsInfo).prefix

        // The mapping folder (DatasetMappingRepo) is the single owner of the
        // mapping that goes into the SIP. Before reading it, heal it:

        // 1) Ingest a zip-borne mapping the folder doesn't know yet (legacy
        //    datasets, uploads predating unconditional ingest) — only when
        //    the zip is newer than the folder's latest version, so editor or
        //    default work done after the upload is never clobbered.
        val repo = datasetContext.datasetMappingRepo
        datasetContext.sipRepo.latestSipOpt.foreach { sip =>
          sip.rawMappingXmlOpt.foreach { case (zipPrefix, xml) =>
            val latestFolderMillis = repo.listVersions.headOption.map(_.timestamp.getMillis).getOrElse(0L)
            if (zipPrefix == targetPrefix &&
                !repo.hasVersion(DefaultMappingRepo.computeHash(xml)) &&
                sip.file.lastModified > latestFolderMillis) {
              val v = repo.saveFromSipUpload(xml, zipPrefix, Some("Ingested from SIP zip during generation"))
              logger.info(s"Dataset $spec: ingested newer zip mapping into folder (hash=${v.hash})")
            }
          }
        }

        // 2) Default mode: materialize the selected default into the folder
        //    when current differs — generation never reads the default store
        //    directly, so folder history records every default update.
        if (dsInfo.usesDefaultMapping) {
          (for {
            prefix <- dsInfo.getDefaultMappingPrefix
            name <- dsInfo.getDefaultMappingName
            version = dsInfo.getDefaultMappingVersion.getOrElse("latest")
            xml <- new DefaultMappingRepo(datasetContext.orgContext.orgRoot).getXml(prefix, name, version)
          } yield (prefix, name, version, xml)) match {
            case Some((prefix, name, version, xml)) if prefix == targetPrefix =>
              val hash = DefaultMappingRepo.computeHash(xml)
              if (!repo.getCurrentVersionHash.contains(hash)) {
                if (repo.hasVersion(hash)) repo.setCurrentVersion(hash)
                else repo.saveFromDefault(xml, prefix, version, Some(s"Materialized default $prefix/$name@$version at generation"))
                logger.info(s"Dataset $spec: default mapping $prefix/$name@$version is now folder-current")
              }
            case Some((prefix, _, _, _)) =>
              logger.warn(s"Dataset $spec: default mapping prefix '$prefix' does not match target '$targetPrefix' — ignored")
            case None =>
              logger.warn(s"Dataset $spec configured for default mapping but the default was not found")
          }
        }

        // Single read path: folder-current for the target prefix, or nothing.
        // A cross-prefix folder ("Cannot find dyn-opt path ..." class) or an
        // empty folder yields a skeleton SIP — never a stale mapping.
        val effectiveMappingXml: Option[String] = repo.getInfo match {
          case Some(info) if info.prefix == targetPrefix =>
            val xml = repo.getXml("current")
            if (xml.isEmpty)
              logger.warn(s"Dataset $spec: mapping folder has no current version for '$targetPrefix' — building skeleton SIP")
            xml
          case Some(info) =>
            logger.warn(s"Dataset $spec: mapping folder holds prefix '${info.prefix}' but dataset targets '$targetPrefix' — building skeleton SIP")
            None
          case None =>
            logger.info(s"Dataset $spec: no mapping in folder for '$targetPrefix' — building skeleton SIP")
            None
        }

        // Reuse the prior SIP only when the folder supplied a mapping (which
        // replaces the SIP's embedded one wholesale) AND its prefix matches.
        // With no folder mapping a fresh skeleton is built instead — the old
        // SIP's embedded mapping must never be carried forward verbatim.
        val reusableLatestSip = datasetContext.sipRepo.latestSipOpt.filter { latestSip =>
          effectiveMappingXml.isDefined &&
            latestSip.sipMappingOpt.map(_.prefix).contains(targetPrefix)
        }

        // Existing SIPs are deleted only AFTER the new one is built
        // successfully — a failed build must never destroy the dataset's
        // only mapping carrier (observed: a failing generation deleted the
        // good SIP and every retry reused its own corrupted output).
        val priorSips = datasetContext.sipFiles.toList
        val sipBuilt: Either[String, File] = reusableLatestSip match {
          case Some(latestSip) =>
            val prefixRepoOpt = latestSip.sipMappingOpt.flatMap(mapping =>
              datasetContext.orgContext.sipFactory.prefixRepo(mapping.prefix, recDefVersionHashOpt))
            val sipFile = datasetContext.createSipFile
            try {
              latestSip.copyWithSourceTo(sipFile, pocketFile, prefixRepoOpt, SipGenerationFacts(dsInfo), effectiveMappingXml)
              Right(sipFile)
            } catch {
              case e: Exception =>
                sipFile.delete()
                throw e
            }
          case None =>
            val facts = SipGenerationFacts(dsInfo)
            logger.info(s"Generating fresh SIP for $spec on prefix=${facts.prefix} (no reusable prior SIP for that prefix)")
            datasetContext.orgContext.sipFactory.prefixRepo(facts.prefix, recDefVersionHashOpt) match {
              case Some(prefixRepo) =>
                val sipFile = datasetContext.createSipFile
                    try {
                  prefixRepo.initiateSipZip(sipFile, pocketFile, facts, effectiveMappingXml)
                  Right(sipFile)
                } catch {
                  case e: Exception =>
                    sipFile.delete()
                    throw e
                }
              case None =>
                    Left("Unable to build sip for download")
            }
        }

        sipBuilt match {
          case Left(message) => StageFailed(message)
          case Right(sipFile) =>
            if (pocketCount > 0) {
              priorSips.filterNot(_ == sipFile).foreach(_.delete())
              SipGenerated(pocketCount)
            } else {
              sipFile.delete()
              deleteQuietly(datasetContext.pocketFile)
              deleteQuietly(manifestFile)
              StageFailed(
                "Zero pockets generated. You probably forgot te set the record root and unique identifier.")
            }
        }
    }
  }
}
