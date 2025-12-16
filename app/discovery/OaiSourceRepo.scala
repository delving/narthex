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

package discovery

import java.io.File
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import discovery.OaiSourceConfig._

/**
 * Repository for OAI-PMH discovery sources.
 * Stores configurations as JSON in the file system.
 *
 * Storage location: {orgRoot}/oai-sources/sources.json
 */
class OaiSourceRepo(orgRoot: File) {

  private val logger = Logger(getClass)
  private val sourcesDir = new File(orgRoot, "oai-sources")
  private val sourcesFile = new File(sourcesDir, "sources.json")

  // Ensure directory exists
  if (!sourcesDir.exists()) {
    sourcesDir.mkdirs()
    logger.info(s"Created OAI sources directory: $sourcesDir")
  }

  /**
   * Load all sources from storage.
   */
  def listSources(): List[OaiSource] = {
    if (!sourcesFile.exists()) {
      List.empty
    } else {
      try {
        val content = FileUtils.readFileToString(sourcesFile, "UTF-8")
        Json.parse(content).as[List[OaiSource]]
      } catch {
        case e: Exception =>
          logger.error(s"Error reading sources file: ${e.getMessage}", e)
          List.empty
      }
    }
  }

  /**
   * Get a source by ID.
   */
  def getSource(id: String): Option[OaiSource] = {
    listSources().find(_.id == id)
  }

  /**
   * Create a new source.
   * Generates ID from name if not provided.
   */
  def createSource(source: OaiSource): OaiSource = {
    val sources = listSources()

    // Generate ID if empty
    val id = if (source.id.isEmpty) generateSlug(source.name) else source.id

    // Ensure unique ID
    val uniqueId = ensureUniqueId(id, sources.map(_.id))

    val newSource = source.copy(
      id = uniqueId,
      createdAt = DateTime.now()
    )

    saveSources(sources :+ newSource)
    logger.info(s"Created OAI source: ${newSource.id} (${newSource.name})")
    newSource
  }

  /**
   * Update an existing source.
   */
  def updateSource(id: String, source: OaiSource): Option[OaiSource] = {
    val sources = listSources()
    sources.find(_.id == id) match {
      case Some(existing) =>
        val updated = source.copy(
          id = id,  // Keep original ID
          createdAt = existing.createdAt  // Keep original creation time
        )
        val newList = sources.map(s => if (s.id == id) updated else s)
        saveSources(newList)
        logger.info(s"Updated OAI source: $id")
        Some(updated)
      case None =>
        logger.warn(s"Source not found for update: $id")
        None
    }
  }

  /**
   * Delete a source by ID.
   */
  def deleteSource(id: String): Boolean = {
    val sources = listSources()
    val newList = sources.filterNot(_.id == id)
    if (newList.length < sources.length) {
      saveSources(newList)
      logger.info(s"Deleted OAI source: $id")
      true
    } else {
      logger.warn(s"Source not found for deletion: $id")
      false
    }
  }

  /**
   * Add setSpecs to the ignore list for a source.
   */
  def addIgnoredSets(sourceId: String, setSpecs: List[String]): Option[OaiSource] = {
    getSource(sourceId).flatMap { source =>
      val newIgnored = (source.ignoredSets ++ setSpecs).distinct
      updateSource(sourceId, source.copy(ignoredSets = newIgnored))
    }
  }

  /**
   * Remove setSpecs from the ignore list for a source.
   */
  def removeIgnoredSets(sourceId: String, setSpecs: List[String]): Option[OaiSource] = {
    getSource(sourceId).flatMap { source =>
      val newIgnored = source.ignoredSets.filterNot(setSpecs.contains)
      updateSource(sourceId, source.copy(ignoredSets = newIgnored))
    }
  }

  /**
   * Update the lastChecked timestamp for a source.
   */
  def updateLastChecked(sourceId: String): Option[OaiSource] = {
    getSource(sourceId).flatMap { source =>
      updateSource(sourceId, source.copy(lastChecked = Some(DateTime.now())))
    }
  }

  /**
   * Normalize a setSpec for use as a Narthex dataset identifier.
   * Example: "enb_05.documenten" -> "enb-05-documenten"
   */
  def normalizeSetSpec(setSpec: String): String = {
    setSpec
      .replace(".", "-")
      .replace("_", "-")
      .replaceAll("[^a-zA-Z0-9-]", "-")
      .replaceAll("-+", "-")
      .replaceAll("^-|-$", "")
      .toLowerCase
  }

  /**
   * Match a normalized spec against the mapping rules for a source.
   * Returns the first matching rule, if any.
   */
  def matchMappingRule(normalizedSpec: String, rules: List[MappingRule]): Option[MappingRule] = {
    rules.find { rule =>
      try {
        normalizedSpec.matches(rule.pattern)
      } catch {
        case e: Exception =>
          logger.warn(s"Invalid regex pattern: ${rule.pattern} - ${e.getMessage}")
          false
      }
    }
  }

  // --- Private helpers ---

  private def saveSources(sources: List[OaiSource]): Unit = {
    val json = Json.prettyPrint(Json.toJson(sources))
    FileUtils.writeStringToFile(sourcesFile, json, "UTF-8")
  }

  /**
   * Generate a slug from a display name.
   * "Memorix Maior ENB" -> "memorix-maior-enb"
   */
  private def generateSlug(name: String): String = {
    name.toLowerCase
      .replaceAll("[^a-z0-9\\s-]", "")
      .replaceAll("\\s+", "-")
      .replaceAll("-+", "-")
      .replaceAll("^-|-$", "")
  }

  /**
   * Ensure ID is unique by appending a counter if needed.
   */
  private def ensureUniqueId(baseId: String, existingIds: List[String]): String = {
    if (!existingIds.contains(baseId)) {
      baseId
    } else {
      var counter = 2
      var candidate = s"$baseId-$counter"
      while (existingIds.contains(candidate)) {
        counter += 1
        candidate = s"$baseId-$counter"
      }
      candidate
    }
  }
}
