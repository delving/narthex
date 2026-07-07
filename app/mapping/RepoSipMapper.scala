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

package mapping

import java.io.ByteArrayInputStream

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

import eu.delving.metadata.RecMapping
import play.api.Logger

import dataset.DatasetContext
import dataset.Sip
import dataset.Sip.SipMapper
import dataset.PocketMappingEngine
import dataset.SipFactory.SipGenerationFacts

/**
 * Build a SipMapper from the DatasetMappingRepo + RecDefRepo — the mapping
 * single-owner path (Phase A4a). The SIP zip plays no role: the mapping
 * comes from the repo's "current" version, the record definition from the
 * dataset's pinned (or current) rec-def, and the facts from dsInfo.
 *
 * Returns None (with a logged reason) when the repo has no current mapping
 * for the dataset's prefix — the caller may fall back to the legacy
 * zip-backed mapper while narthex.mapping.repoBacked rolls out.
 */
object RepoSipMapper {

  private val logger = Logger(getClass)

  def build(datasetContext: DatasetContext): Option[SipMapper] = {
    val dsInfo = datasetContext.dsInfo
    val orgContext = datasetContext.orgContext
    val spec = dsInfo.spec
    val repo = datasetContext.datasetMappingRepo
    val facts = SipGenerationFacts(dsInfo)

    repo.getInfo match {
      case None =>
        logger.debug(s"RepoSipMapper: no mapping repo metadata for $spec")
        None
      case Some(info) if info.prefix != facts.prefix =>
        logger.warn(s"RepoSipMapper: repo mapping is for prefix '${info.prefix}' but $spec targets '${facts.prefix}' — no repo-backed mapper")
        None
      case Some(info) =>
        repo.getXml("current") match {
          case None =>
            logger.info(s"RepoSipMapper: no current mapping for $spec (prefix '${info.prefix}')")
            None
          case Some(xml) =>
            orgContext.sipFactory.prefixRepo(info.prefix, dsInfo.getRecDefVersionHash) match {
              case None =>
                logger.warn(s"RepoSipMapper: no rec-def available for prefix '${info.prefix}' — no repo-backed mapper for $spec")
                None
              case Some(prefixRepo) =>
                Try {
                  val tree = Sip.loadRecDefTree(prefixRepo.recordDefinition)
                  val in = new ByteArrayInputStream(xml.getBytes("UTF-8"))
                  val recMapping = try RecMapping.read(in, tree) finally in.close()
                  // Facts come from the dataset, not from whatever the stored
                  // XML happens to carry (same rewrite generation performs).
                  prefixRepo.toMap(facts).entrySet().asScala.foreach { e =>
                    recMapping.setFact(e.getKey, e.getValue)
                  }
                  new PocketMappingEngine(
                    spec = spec,
                    prefix = info.prefix,
                    recDefTree = tree,
                    recMapping = recMapping,
                    validatorOpt = None, // XSD validation is off by default (Sip.XSD_VALIDATION)
                    orgId = orgContext.appConfig.orgId
                  )
                } match {
                  case Success(mapper) => Some(mapper)
                  case Failure(e) =>
                    logger.error(s"RepoSipMapper: current mapping for $spec does not build against rec-def '${info.prefix}': ${e.getMessage}")
                    None
                }
            }
        }
    }
  }
}
