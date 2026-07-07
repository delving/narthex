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
        val pocketOutput = new FileOutputStream(pocketFile)
        val idFilter = dsInfo.getIdFilter
        val pocketCount =
          sourceRepo.generatePockets(pocketOutput, idFilter, ctx.progressReporter)

        val recDefVersionHashOpt = dsInfo.getRecDefVersionHash
        val targetPrefix = SipGenerationFacts(dsInfo).prefix

        // Effective mapping XML based on mapping source configuration
        val effectiveMappingXml: Option[String] = if (dsInfo.usesDefaultMapping) {
          (for {
            prefix <- dsInfo.getDefaultMappingPrefix
            name <- dsInfo.getDefaultMappingName
            version = dsInfo.getDefaultMappingVersion.getOrElse("latest")
            defaultMappingRepo = new DefaultMappingRepo(datasetContext.orgContext.orgRoot)
            xml <- defaultMappingRepo.getXml(prefix, name, version)
          } yield {
            logger.info(s"Using default mapping for dataset $spec: prefix=$prefix, name=$name, version=$version")
            xml
          }).orElse {
            logger.warn(s"Dataset $spec configured for default mapping but mapping not found, falling back to manual")
            None
          }
        } else {
          // Manual mode: DatasetMappingRepo, auto-migrating legacy SIP mappings
          val repo = datasetContext.datasetMappingRepo
          if (repo.listVersions.isEmpty) {
            datasetContext.sipRepo.latestSipOpt.foreach { sip =>
              repo.ensureMigratedFromSip(sip).foreach { v =>
                logger.info(s"Auto-migrated mapping from SIP for $spec (hash=${v.hash})")
              }
            }
          }
          // The stored mapping belongs to ONE prefix. After a cross-prefix
          // switch, injecting it into a SIP of the new prefix makes sip-core
          // resolve its dyn-opt paths against the wrong record definition
          // ("Cannot find dyn-opt path ..."). Ignore a mismatched mapping —
          // the SIP falls back to its own (matching) mapping.
          repo.getInfo.map(_.prefix).filter(_ != targetPrefix) match {
            case Some(repoPrefix) =>
              logger.warn(
                s"Dataset $spec: stored mapping is for prefix '$repoPrefix' but the dataset now targets " +
                  s"'$targetPrefix' — ignoring it. Upload a SIP or save a mapping for '$targetPrefix' to replace it.")
              None
            case None =>
              repo.getXml("current") match {
                case Some(xml) =>
                  logger.info(s"Using mapping from DatasetMappingRepo for dataset $spec (manual mode)")
                  Some(xml)
                case None =>
                  logger.warn(
                    s"Dataset $spec: manual mode but DatasetMappingRepo has no current mapping — " +
                      s"SIP regeneration will reuse the prior SIP's mapping (stale-mapping risk). " +
                      s"Open the mapping editor or upload a fresh SIP to register a current version.")
                  None
              }
          }
        }

        // Only reuse the latest SIP when its mapping prefix matches the
        // dataset's CURRENT prefix — after a cross-prefix switch the old
        // SIP's internals must not be carried forward.
        val reusableLatestSip = datasetContext.sipRepo.latestSipOpt.filter { latestSip =>
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
            pocketOutput.close()
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
                pocketOutput.close()
                try {
                  prefixRepo.initiateSipZip(sipFile, pocketFile, facts, effectiveMappingXml)
                  Right(sipFile)
                } catch {
                  case e: Exception =>
                    sipFile.delete()
                    throw e
                }
              case None =>
                pocketOutput.close()
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
              StageFailed(
                "Zero pockets generated. You probably forgot te set the record root and unique identifier.")
            }
        }
    }
  }
}
