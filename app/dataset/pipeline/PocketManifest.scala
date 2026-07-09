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

import java.io.File

import play.api.libs.json._

import dataset.SourceRepo.IdFilter

/**
 * Sidecar manifest proving which inputs produced pockets.xml (plan §2.6:
 * any derived file carries a token proving its inputs). Pocket generation
 * streams the whole accumulated source — the slowest part of make-sip —
 * so when the manifest matches the current inputs the stage skips it.
 *
 * Inputs covered: every source zip (name/size/mtime), deleted.ids,
 * source_facts.txt, the id filter, and a generator version to invalidate
 * everything on format changes. Integrity: the stored pocket file
 * size/mtime must still match, or the cache is ignored.
 */
object PocketManifest {

  val GENERATOR_VERSION = 1

  private def fileStat(f: File): JsValue =
    if (f.exists()) Json.obj("size" -> f.length(), "mtime" -> f.lastModified()) else JsNull

  /** The input fingerprint (without the output part). */
  def inputs(sourceDir: File, idFilter: IdFilter): JsObject = {
    val zips = Option(sourceDir.listFiles())
      .getOrElse(Array.empty[File])
      .filter(f => f.isFile && f.getName.endsWith(".zip"))
      .sortBy(_.getName)
      .map(f => Json.obj("name" -> f.getName, "size" -> f.length(), "mtime" -> f.lastModified()))
    Json.obj(
      "generatorVersion" -> GENERATOR_VERSION,
      "sourceZips" -> JsArray(zips.toIndexedSeq),
      "deletedIds" -> fileStat(new File(sourceDir, "deleted.ids")),
      "sourceFacts" -> fileStat(new File(sourceDir, "source_facts.txt")),
      "idFilter" -> s"${idFilter.filterType}:${idFilter.filterExpression.getOrElse("")}"
    )
  }

  /** Pocket count from a matching manifest, or None when anything moved. */
  def cachedCount(manifestFile: File, currentInputs: JsObject, pocketFile: File): Option[Int] =
    if (!manifestFile.exists() || !pocketFile.exists()) None
    else scala.util.Try {
      val stored = Json.parse(org.apache.commons.io.FileUtils.readFileToString(manifestFile, "UTF-8"))
      val inputsMatch = (stored \ "inputs").asOpt[JsObject].contains(currentInputs)
      val outputIntact = (stored \ "pocket" \ "size").asOpt[Long].contains(pocketFile.length()) &&
        (stored \ "pocket" \ "mtime").asOpt[Long].contains(pocketFile.lastModified())
      if (inputsMatch && outputIntact) (stored \ "pocketCount").asOpt[Int] else None
    }.toOption.flatten

  def write(manifestFile: File, currentInputs: JsObject, pocketCount: Int, pocketFile: File): Unit = {
    val json = Json.obj(
      "inputs" -> currentInputs,
      "pocketCount" -> pocketCount,
      "pocket" -> Json.obj("size" -> pocketFile.length(), "mtime" -> pocketFile.lastModified())
    )
    org.apache.commons.io.FileUtils.writeStringToFile(manifestFile, Json.prettyPrint(json), "UTF-8")
  }

  def manifestFileFor(pocketFile: File): File =
    new File(pocketFile.getParentFile, pocketFile.getName + ".manifest.json")
}
