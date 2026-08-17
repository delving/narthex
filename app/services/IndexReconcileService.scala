package services

import organization.OrgContext
import play.api.Logging

import scala.concurrent.{ExecutionContext, Future}

/**
 * Registry <-> index reconciliation. index_verify reports COUNTS only; when
 * the numbers disagree, only a full id comparison can say WHICH records are
 * missing. Hub3 streams every indexed hubID for the spec
 * (/api/admin/index-ids), we diff against the registry's live records, and
 * reset the sent-state of the missing ones so the next save re-sends exactly
 * those (observed: 3 records of enb-342-beeldmateriaal silently never landed
 * while the registry said "all sent" — unrecoverable without this diff).
 */
class IndexReconcileService(orgContext: OrgContext)(implicit ec: ExecutionContext) extends Logging {

  case class ReconcileResult(spec: String, indexed: Int, expected: Int,
                             missingReset: Int, orphansInIndex: Int, missingSample: Seq[String])

  /** Fetch indexed ids, diff, reset sent-state for missing. Does NOT save —
    * the caller decides (manual action returns the result; the auto path
    * triggers a save when something was reset). */
  def reconcile(spec: String): Future[ReconcileResult] = {
    val orgId = orgContext.appConfig.orgId
    val url = s"${orgContext.appConfig.naveApiUrl}/api/admin/index-ids/$orgId/$spec"
    orgContext.wsApi.url(url)
      .withRequestTimeout(scala.concurrent.duration.DurationInt(10).minutes)
      .get()
      .map { response =>
        if (response.status != 200)
          throw new RuntimeException(s"index-ids fetch failed (${response.status}) for $spec")
        val prefix = s"${orgId}_${spec}_"
        val indexedLocalIds: Set[String] = response.body.linesIterator
          .filter(_.nonEmpty)
          .map(id => if (id.startsWith(prefix)) id.substring(prefix.length) else id)
          .toSet
        val registryIds = orgContext.recordRegistry.listSeenLocalIds(spec)
        val missing = registryIds.filterNot(indexedLocalIds.contains)
        val orphans = indexedLocalIds.size - (registryIds.size - missing.size)
        val reset = orgContext.recordRegistry.resetSentStateForIds(spec, missing)
        if (missing.nonEmpty)
          logger.warn(s"Index reconcile $spec: ${missing.size} record(s) in registry but not indexed — sent-state reset ($reset). Sample: ${missing.take(3).mkString(", ")}")
        if (orphans > 0)
          logger.warn(s"Index reconcile $spec: $orphans orphan doc(s) in index without a live registry record — a full save's index_verify/clear path owns their removal")
        ReconcileResult(spec, indexedLocalIds.size, registryIds.size, reset, math.max(0, orphans), missing.take(10).toSeq)
      }
  }
}
