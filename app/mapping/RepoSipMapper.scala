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
 * zip-backed mapper while narthex.mapping.repoBacked rolls out. A current
 * mapping that does not build throws instead: falling back would run an
 * older mapping without anyone noticing.
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
            fromXml(datasetContext, info.prefix, xml) match {
              case Right(mapper) => Some(mapper)
              case Left(reason) =>
                throw new RuntimeException(s"The current mapping for $spec: $reason")
            }
        }
    }
  }

  /**
   * Build a SipMapper from mapping XML that need not be stored anywhere: the
   * repo's current version (build) or an editor's unsaved mapping (preview).
   * The rec-def is the dataset's pinned (or current) one for the prefix, and
   * the facts come from dsInfo, never from the XML.
   *
   * @return the mapper, or Left with a reason when the prefix has no rec-def
   *         or the XML does not build against it
   */
  def fromXml(datasetContext: DatasetContext, prefix: String, xml: String): Either[String, SipMapper] = {
    val dsInfo = datasetContext.dsInfo
    val orgContext = datasetContext.orgContext
    orgContext.sipFactory.prefixRepo(prefix, dsInfo.getRecDefVersionHash) match {
      case None => Left(s"no rec-def available for prefix '$prefix'")
      case Some(prefixRepo) =>
        Try {
          val tree = Sip.loadRecDefTree(prefixRepo.recordDefinition)
          val in = new ByteArrayInputStream(xml.getBytes("UTF-8"))
          val recMapping = try RecMapping.read(in, tree) finally in.close()
          // Facts come from the dataset, not from whatever the XML happens
          // to carry (same rewrite generation performs).
          prefixRepo.toMap(SipGenerationFacts(dsInfo)).entrySet().asScala.foreach { e =>
            recMapping.setFact(e.getKey, e.getValue)
          }
          new PocketMappingEngine(
            spec = dsInfo.spec,
            prefix = prefix,
            recDefTree = tree,
            recMapping = recMapping,
            validatorOpt = None, // XSD validation is off by default (Sip.XSD_VALIDATION)
            orgId = orgContext.appConfig.orgId
          ): SipMapper
        } match {
          case Success(mapper) => Right(mapper)
          case Failure(e) =>
            val causes = Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(5).map(_.getMessage).toSeq.distinct
            Left(s"mapping does not build against rec-def '$prefix': ${causes.mkString("\n")}")
        }
    }
  }
}
