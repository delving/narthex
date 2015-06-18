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

package analysis

import java.io.File
import java.util.UUID

import analysis.NodeRepo._
import dataset.DatasetContext
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FileUtils._
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import services.FileHandling.createReader
import services.StringHandling.pathToDirectory

import scala.collection.mutable
import scala.language.postfixOps

object NodeRepo {
  def apply(parent: DatasetContext, parentDir: File, tag: String) = {
    val dir = if (tag == null) parentDir else new File(parentDir, pathToDirectory(tag))
    dir.mkdirs()
    new NodeRepo(parent, dir)
  }

  def readJson(file: File) = Json.parse(readFileToString(file))

  def createJson(file: File, content: JsObject) = writeStringToFile(file, Json.prettyPrint(content), "UTF-8")

  def updateJson(file: File)(xform: JsValue => JsObject) = {
    if (file.exists()) {
      val value = readJson(file)
      val tempFile = new File(file.getParentFile, s"${file.getName}.temp")
      createJson(tempFile, xform(value))
      deleteQuietly(file)
      moveFile(tempFile, file)
    }
    else {
      writeStringToFile(file, Json.prettyPrint(xform(Json.obj())), "UTF-8")
    }
  }
}

class NodeRepo(val parent: DatasetContext, val dir: File) {

  def child(childTag: String) = NodeRepo(parent, dir, childTag)

  def f(name: String) = new File(dir, name)

  def status = f("status.json")

  def setStatus(content: JsObject) = createJson(status, content)

  def values = f("values.txt")

  def tempSort = f(s"sorting-${UUID.randomUUID()}.txt")

  def sorted = f("sorted.txt")

  def counted = f("counted.txt")

  val sizeFactor = 5 // relates to the lists below

  def histogramJson = List(100, 500, 2500, 12500).map(size => (size, f(s"histogram-$size.json")))

  def largestHistogram: Option[JsValue] = {
    val existingFiles = histogramJson.map(_._2).filter(f => f.exists())
    existingFiles.lastOption.map(f => Json.parse(FileUtils.readFileToString(f, "UTF-8")))
  }

  def sampleJson = List(100, 500, 2500).map(size => (size, f(s"sample-$size.json")))

  def uriText = f("uri.txt")

  def uniqueText = f("unique.txt")

  def histogramText = f("histogram.txt")

  def writeHistograms(uniqueCount: Int) = {

    val LINE = """^ *(\d*) (.*)$""".r
    val input = createReader(histogramText)

    def lineOption = Option(input.readLine())

    def createFile(maximum: Int, entries: mutable.ArrayBuffer[JsArray], histogramFile: File) = {
      val uri = readFileToString(uriText)
      createJson(histogramFile, Json.obj(
        "tag" -> dir.getName,
        "uri" -> uri,
        "uniqueCount" -> uniqueCount,
        "entries" -> entries.size,
        "maximum" -> maximum,
        "complete" -> (entries.size == uniqueCount),
        "histogram" -> entries
      ))
    }

    var activeCounters = histogramJson.map(pair => (pair._1, new mutable.ArrayBuffer[JsArray], pair._2))
    activeCounters = activeCounters.filter(pair => pair._1 == activeCounters.head._1 || uniqueCount > pair._1 / sizeFactor)
    val counters = activeCounters
    var line = lineOption
    var count = 1
    while (line.isDefined && activeCounters.nonEmpty) {
      val lineMatch = LINE.findFirstMatchIn(line.get)
      activeCounters = activeCounters.filter {
        triple =>
          lineMatch.map(groups => triple._2 += Json.arr(groups.group(1), groups.group(2)))
          val keep = count < triple._1
          if (!keep) createFile(triple._1, triple._2, triple._3) // side effect
          keep
      }
      line = lineOption
      count += 1
    }
    activeCounters.foreach(triple => createFile(triple._1, triple._2, triple._3))
    counters.map(triple => triple._1)

  }
}

