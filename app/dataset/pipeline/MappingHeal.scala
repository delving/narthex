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

import play.api.Logger

import dataset.DatasetContext
import mapping.DefaultMappingRepo

/**
 * Default-mode healing shared by the generate-sip AND process stages: when
 * the dataset tracks a shared default mapping, materialize the selected
 * version into the dataset's mapping folder whenever folder-current
 * differs. The folder stays the single owner; nothing ever reads the
 * default store at execution time. Runs at process dispatch too so a
 * "start processing" without a preceding make-sip still picks up an
 * updated default.
 */
object MappingHeal {

  private val logger = Logger(getClass)

  def materializeSelectedDefault(datasetContext: DatasetContext, targetPrefix: String): Unit = {
    val dsInfo = datasetContext.dsInfo
    val spec = dsInfo.spec
    if (!dsInfo.usesDefaultMapping) return
    val repo = datasetContext.datasetMappingRepo
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
          else repo.saveFromDefault(xml, prefix, version, Some(s"Materialized default $prefix/$name@$version"))
          logger.info(s"Dataset $spec: default mapping $prefix/$name@$version is now folder-current")
        }
      case Some((prefix, _, _, _)) =>
        logger.warn(s"Dataset $spec: default mapping prefix '$prefix' does not match target '$targetPrefix' — ignored")
      case None =>
        logger.warn(s"Dataset $spec configured for default mapping but the default was not found")
    }
  }
}
