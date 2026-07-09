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

package services

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.jdk.CollectionConverters._

import play.api.Logger

import dataset.DsInfo
import organization.OrgContext
import triplestore.Sparql.selectDatasetSpecsQ
import triplestore.TripleStore

/**
 * Phase D2 one-shot migration: Fuseki dataset-info graphs → datasets.db.
 * Runs at startup ONLY while datasets.db is empty; once populated, Fuseki
 * is never read again (decision 2026-07-09: migration, not fallback).
 *
 * Also seeds a synthetic baseline run in records.db for datasets that had
 * a stateSaved prop but no registry history, so saved-status is honest
 * from cutover day one (decision 2026-07-10).
 */
object FusekiMigration {

  private val logger = Logger(getClass)

  def runIfNeeded(orgContext: OrgContext)(implicit ec: ExecutionContext, ts: TripleStore): Unit = {
    val db = orgContext.datasetsDb
    if (!db.isEmpty) {
      logger.info("datasets.db populated — Fuseki migration not needed")
      return
    }
    if (orgContext.narthexConfig.tripleStoreUrl.isEmpty) {
      logger.info("no triple-store configured — starting with an empty dataset registry (migration slice inert)")
      return
    }
    logger.info("datasets.db is EMPTY — one-shot migration from Fuseki starting")
    val prefix = orgContext.appConfig.nxUriPrefix
    val specs =
      try Await.result(ts.query(selectDatasetSpecsQ), 5.minutes).map(_("spec").text)
      catch {
        case e: Exception =>
          logger.error(s"Fuseki migration: could not list datasets (${e.getMessage}) — starting with an empty registry")
          return
      }
    var migrated = 0
    var seeded = 0
    specs.foreach { spec =>
      try {
        val model = Await.result(ts.dataGet(DsInfo.getGraphName(spec, prefix)), 1.minute)
        val subject = model.getResource(DsInfo.getDsInfoUri(spec, prefix))
        val literalStmts = model.listStatements(subject, null, null).asScala.toList
          .filter(_.getObject.isLiteral)
          .map(st => st.getPredicate.getLocalName -> st.getObject.asLiteral().getString)
        db.createDataset(spec)
        val (multi, single) = literalStmts.groupBy(_._1).partition(_._2.size > 1)
        if (single.nonEmpty) db.setProps(spec, single.map(_._2.head).toSeq: _*)
        multi.foreach { case (prop, vals) =>
          vals.foreach { case (_, v) => db.addListValue(spec, prop, v) }
        }
        migrated += 1

        // Baseline seeding: prior saved state, no registry history
        val savedAt = literalStmts.collectFirst { case ("stateSaved", v) => v }
        if (savedAt.isDefined && orgContext.recordRegistry.listRuns(spec, 36500).isEmpty) {
          orgContext.recordRegistry.seedBaselineRun(spec, savedAt.get)
          seeded += 1
        }
      } catch {
        case e: Exception =>
          logger.error(s"Fuseki migration failed for $spec: ${e.getMessage}")
      }
    }
    logger.info(s"Fuseki migration complete: $migrated dataset(s) migrated, $seeded baseline run(s) seeded")
  }
}
