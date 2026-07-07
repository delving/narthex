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

import dataset.DsInfo.DsState
import mapping.DatasetMappingRepo
import organization.OrgContext
import services.Temporal
import triplestore.GraphProperties

/**
 * Phase A4b: dataset lifecycle status DERIVED from what actually exists —
 * disk artifacts, the mapping folder, and registry runs — instead of stored
 * RDF state props stamped by ~40 scattered call sites. A state is present
 * iff its artifact is present, so the stale-state false-positive class
 * (e.g. PROCESSABLE without a mapping) is structurally gone.
 *
 * Not derived (stay stored props): DISABLED (admin action), error/retry
 * props (events), processed valid/invalid counts (only known at process
 * time). saved/incrementalSaved fall back to the legacy props for datasets
 * predating the record registry.
 *
 * The core takes primitives (no DsInfo) so the dataset-list-light path can
 * project from one SPARQL row without a per-dataset Fuseki model fetch.
 *
 * Timestamps are artifact mtimes / run completion times — good for display,
 * NOT guaranteed pipeline-ordered (a SIP regenerated after processing is
 * newer than the processed output). Consumers must order by the lattice in
 * `currentState`, never by comparing these timestamps.
 */
object DatasetStatusProjector {

  private val logger = play.api.Logger(getClass)

  case class ProjectedStatus(
    raw: Option[DateTime],
    rawAnalyzed: Option[DateTime],
    sourced: Option[DateTime],
    sourceAnalyzed: Option[DateTime],
    mappable: Option[DateTime],
    processable: Option[DateTime],
    processed: Option[DateTime],
    analyzed: Option[DateTime],
    saved: Option[DateTime],
    incrementalSaved: Option[DateTime],
    disabled: Option[DateTime]
  ) {

    /** Furthest-along state by explicit lattice; DISABLED trumps all. */
    def currentState: DsState.Value = {
      if (disabled.isDefined) DsState.DISABLED
      else if (saved.isDefined || incrementalSaved.isDefined) {
        val savedAt = saved.map(_.getMillis).getOrElse(0L)
        val incAt = incrementalSaved.map(_.getMillis).getOrElse(0L)
        if (incAt > savedAt) DsState.INCREMENTAL_SAVED else DsState.SAVED
      }
      else if (analyzed.isDefined) DsState.ANALYZED
      else if (processed.isDefined) DsState.PROCESSED
      else if (processable.isDefined) DsState.PROCESSABLE
      else if (mappable.isDefined) DsState.MAPPABLE
      else if (sourceAnalyzed.isDefined) DsState.SOURCE_ANALYZED
      else if (sourced.isDefined) DsState.SOURCED
      else if (rawAnalyzed.isDefined) DsState.RAW_ANALYZED
      else if (raw.isDefined) DsState.RAW
      else DsState.EMPTY
    }

    /** Flat JSON field pairs, same names the UI has always read. */
    def stateFields: Seq[(String, String)] = Seq(
      "stateRaw" -> raw,
      "stateRawAnalyzed" -> rawAnalyzed,
      "stateSourced" -> sourced,
      "stateSourceAnalyzed" -> sourceAnalyzed,
      "stateMappable" -> mappable,
      "stateProcessable" -> processable,
      "stateProcessed" -> processed,
      "stateAnalyzed" -> analyzed,
      "stateSaved" -> saved,
      "stateIncrementalSaved" -> incrementalSaved,
      "stateDisabled" -> disabled
    ).collect { case (name, Some(dt)) => name -> Temporal.timeToString(dt) }
  }

  /** Convenience overload when a DatasetContext (with its DsInfo) is in hand. */
  def project(datasetContext: DatasetContext): ProjectedStatus = {
    val dsInfo = datasetContext.dsInfo
    def prop(p: GraphProperties.NXProp): Option[String] = dsInfo.getLiteralProp(p)
    project(
      datasetContext.orgContext,
      spec = dsInfo.spec,
      targetPrefix = prop(GraphProperties.datasetMapToPrefix).getOrElse(""),
      savedFallback = prop(GraphProperties.stateSaved),
      incrementalSavedFallback = prop(GraphProperties.stateIncrementalSaved),
      disabledFallback = prop(GraphProperties.stateDisabled)
    )
  }

  /**
   * DsInfo-free core. The fallback params carry the legacy stored-prop
   * timestamps (ISO strings) for datasets predating the record registry,
   * plus the DISABLED admin flag which is only ever a stored prop.
   */
  def project(
    orgContext: OrgContext,
    spec: String,
    targetPrefix: String,
    savedFallback: Option[String],
    incrementalSavedFallback: Option[String],
    disabledFallback: Option[String]
  ): ProjectedStatus = {

    val datasetRoot = new File(orgContext.datasetsDir, spec)

    def mtime(f: File): Option[DateTime] =
      if (f.exists()) Some(new DateTime(f.lastModified())) else None

    def filesIn(dir: File): Array[File] =
      Option(dir.listFiles()).getOrElse(Array.empty)

    // raw/*.xml or *.xml.gz — mirrors DatasetContext.rawXmlFile
    val raw = filesIn(new File(datasetRoot, "raw"))
      .find(f => f.getName.endsWith(".xml") || f.getName.endsWith(".xml.gz"))
      .flatMap(mtime)

    // processed/NNNNN.xml(.zst) — mirrors ProcessedRepo output naming
    val processedFiles = filesIn(new File(datasetRoot, "processed"))
      .filter(f => f.getName.matches("""\d{5}\.xml(\.zst)?"""))
    val processedIsNonEmpty = processedFiles.nonEmpty
    val processed =
      if (processedIsNonEmpty) Some(new DateTime(processedFiles.map(_.lastModified()).max))
      else None

    // tree/index.json is written by BOTH raw analysis and processed
    // analysis; the processed repo's presence tells which one it is.
    val treeIndex = mtime(new File(datasetRoot, "tree/index.json"))
    val rawAnalyzed = if (processedIsNonEmpty) None else treeIndex
    val analyzed = if (processedIsNonEmpty) treeIndex else None

    // source/source_facts.txt + at least one numbered zip — mirrors SourceRepo
    val sourceDir = new File(datasetRoot, "source")
    val sourceZips = filesIn(sourceDir).filter(_.getName.endsWith(".zip"))
    val sourced =
      if (new File(sourceDir, "source_facts.txt").exists() && sourceZips.nonEmpty)
        Some(new DateTime(sourceZips.map(_.lastModified()).max))
      else None

    val sourceAnalyzed = mtime(new File(datasetRoot, "sourceTree/index.json"))

    // org-level sips/ — mirrors DatasetContext.sipFiles
    val sipFiles = filesIn(orgContext.sipsDir).filter { f =>
      val name = f.getName
      name.endsWith(".sip.zip") && (name.startsWith(s"${spec}__") || name == s"$spec.sip.zip")
    }
    val mappable =
      if (sipFiles.isEmpty) None
      else Some(new DateTime(sipFiles.map(_.lastModified()).max))

    // "processable" = a current mapping exists for the dataset's prefix —
    // folder first, latest SIP zip as legacy fallback. Existence only; a
    // mapping that fails to build surfaces at process time with its reason.
    val processable: Option[DateTime] = {
      val folderMapping = new DatasetMappingRepo(datasetRoot).getInfo
        .filter(info => info.prefix == targetPrefix && info.currentVersion.isDefined)
        .flatMap(info => info.currentVersion.flatMap(h => info.versions.find(_.hash == h)).map(_.timestamp))
      folderMapping.orElse {
        // sipMappingOpt parses the zip's mapping and THROWS on one that does
        // not resolve against its rec-def — one broken dataset must not take
        // down the whole list projection.
        scala.util.Try {
          new SipRepo(orgContext.sipsDir, spec, orgContext.appConfig.rdfBaseUrl).latestSipOpt
            .filter(_.sipMappingOpt.map(_.prefix).contains(targetPrefix))
            .map(sip => new DateTime(sip.file.lastModified()))
        }.recover { case e =>
          logger.warn(s"Projector: unreadable zip mapping for $spec — not processable: ${e.getMessage}")
          None
        }.get
      }
    }

    // Registry runs are the truth for saved status; the legacy stored props
    // cover datasets from before the registry existed.
    val completedRuns = orgContext.recordRegistry
      .listRuns(spec, sinceDays = 3650).filter(_.status == "completed")
    def runTime(kind: String): Option[DateTime] =
      completedRuns.filter(_.kind == kind).flatMap(_.completedAt)
        .sorted.lastOption.map(Temporal.stringToTime)
    def fallbackTime(iso: Option[String]): Option[DateTime] =
      iso.map(Temporal.stringToTime)

    ProjectedStatus(
      raw = raw,
      rawAnalyzed = rawAnalyzed,
      sourced = sourced,
      sourceAnalyzed = sourceAnalyzed,
      mappable = mappable,
      processable = processable,
      processed = processed,
      analyzed = analyzed,
      saved = runTime("full").orElse(fallbackTime(savedFallback)),
      incrementalSaved = runTime("incremental").orElse(fallbackTime(incrementalSavedFallback)),
      disabled = fallbackTime(disabledFallback)
    )
  }
}
