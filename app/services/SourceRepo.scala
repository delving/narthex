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
package services

import java.io.{File, PrintWriter}

import org.apache.commons.io.FileUtils
import services.RecordHandling.RawRecord

import scala.collection.mutable
import scala.io.Source

/**
 * This repository maintains a sequence of XML files, together with files containing
 * identifiers as lines.
 *
 * The ids file has the XML file's ids, and the intersection files (underscore between) contain the ids which
 * are overridden by subsequent id files.  Also there is a .act file indicating how many of the ids are
 * active still (not overridden).
 *
 * Inserting a new file adds new intersection files to previous files, and updates the .act file accordingly.
 *
 * @param dir where do we work
 * @param recordRoot delimit records
 * @param uniqueId identify records
 *
 * @author Gerald de Jong <gerald@delving.eu>
 */

class SourceRepo(val dir: File, recordRoot: String, uniqueId: String) extends RecordHandling {

  def mkdirs = dir.mkdirs()

  private def fileList: List[File] = {
    val files = dir.listFiles()
    if (files == null) List.empty[File] else files.toList
  }

  private def numberString(number: Int): String = "%05d".format(number)

  private def fileNumber(file: File): Int = {
    val s = file.getName
    val num = s.substring(0, s.indexOf('.'))
    num.toInt
  }

  private def xmlName(number: Int): String = s"${numberString(number)}.xml"

  private def idsName(number: Int): String = s"${numberString(number)}.ids"

  private def activeIdsName(number: Int): String = s"${numberString(number)}.act"

  private def xmlFile(number: Int): File = new File(dir, xmlName(number))

  private def idsFile(number: Int): File = new File(dir, idsName(number))

  private def idsFile(file: File): File = idsFile(fileNumber(file))

  private def activeIdsFile(number: Int): File = new File(dir, activeIdsName(number))

  private def activeIdsFile(file: File): File = activeIdsFile(fileNumber(file))

  private def intersectionFile(oldFile: File, newFile: File): File = new File(dir, s"${oldFile.getName}_${newFile.getName}")

  private def nextFileNumber(files: List[File]): Int = {
    files.reverse.headOption match {
      case Some(file) =>
        fileNumber(file) + 1
      case None =>
        0
    }
  }

  private def avoidFiles(file: File): List[File] = {
    val prefix = s"${idsFile(file).getName}_"
    fileList.filter(f => f.getName.startsWith(prefix))
  }

  private def avoidSet(xmlFile: File): Set[String] = {
    var idSet = new mutable.HashSet[String]()
    avoidFiles(xmlFile).foreach(Source.fromFile(_).getLines().foreach(idSet.add))
    idSet.toSet
  }

  private def processFile(fileFiller: File => Unit) = {
    def writeToFile(file: File, string: String): Unit = {
      Some(new PrintWriter(file)).foreach {
        p =>
          p.println(string)
          p.close()
      }
    }
    val files = xmlFiles
    val fileNumber = nextFileNumber(files)
    val file = xmlFile(fileNumber)
    fileFiller(file)
    var idSet = new mutable.HashSet[String]()
    val parser = new RawRecordParser(recordRoot, uniqueId)
    def sendProgress(percent: Int): Boolean = true
    def receiveRecord(record: RawRecord): Unit = idSet.add(record.id)
    def source = Source.fromFile(file)
    parser.parse(source, Set.empty, receiveRecord, -1, sendProgress)
    if (idSet.isEmpty) {
      FileUtils.deleteQuietly(file)
      None
    }
    else {
      val newIdsFile = idsFile(fileNumber)
      FileUtils.write(newIdsFile, idSet.toList.sorted.mkString("\n") + "\n")
      writeToFile(activeIdsFile(newIdsFile), idSet.size.toString)
      var idsFiles = files.map(idsFile)
      idsFiles.foreach { idsFile =>
        val ids = Source.fromFile(idsFile).getLines()
        val intersectionIds = ids.filter(idSet.contains)
        if (intersectionIds.nonEmpty) {
          // create an intersection file
          val intersection = intersectionFile(idsFile, newIdsFile)
          writeToFile(intersection, intersectionIds.mkString("\n"))
          // update the active count
          var avoid = avoidSet(idsFile)
          var activeCount = ids.count(!avoid.contains(_))
          writeToFile(activeIdsFile(idsFile), activeCount.toString)
        }
      }
      Some(file)
    }
  }

  private def xmlFiles = fileList.filter(f => f.isFile && f.getName.endsWith(".xml")).sortBy(_.getName)

  private def nextFileNumber: Int = nextFileNumber(xmlFiles)

  // public things:

  def acceptFile(file: File): Option[File] = processFile(targetFile => FileUtils.moveFile(file, targetFile))

  def acceptPage(page: String): Option[File] = processFile(targetFile => FileUtils.write(targetFile, page, "UTF-8"))

  def parse(output: RawRecord => Unit, sendProgress: Int => Boolean) = {
    val parser = new RawRecordParser(recordRoot, uniqueId)
    val actFiles = fileList.filter(f => f.getName.endsWith(".act"))
    val activeIdCounts = actFiles.map(FileUtils.readFileToString).map(s => s.trim.toInt)
    val totalActiveIds = activeIdCounts.fold(0)(_ + _)
    xmlFiles.foreach { xmlFile =>
      var idSet = avoidSet(xmlFile)
      val source = Source.fromFile(xmlFile)
      parser.parse(source, idSet.toSet, output, totalActiveIds, sendProgress)
      source.close()
    }
  }

}
