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

import java.io._
import java.util.zip.GZIPOutputStream

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
  val MAX_FILES = 100
  val SOURCE_NAME = "source.xml.gz"
  dir.mkdirs()

  private def numberString(number: Int): String = "%05d".format(number)

  private def newSubdirectory(dirs: Seq[File]): File = {
    val number = dirs.sortBy(_.getName).reverse.headOption match {
      case Some(highest) =>
        highest.getName.toInt + 1
      case None =>
        0
    }
    val sub = new File(dir, numberString(number))
    sub.mkdir()
    sub
  }

  private def fileList: Seq[File] = {
    val all = dir.listFiles()
    val (files, dirs) = all.partition(_.isFile)
    dirs.flatMap(_.listFiles()) ++ files
  }

  private def moveFiles() = {
    val all = dir.listFiles()
    val (files, dirs) = all.partition(_.isFile)
    val sub = newSubdirectory(dirs)
    files.filter(_.getName != SOURCE_NAME).foreach(FileUtils.moveFileToDirectory(_, sub, false))
  }

  private def getFileNumber(file: File): Int = {
    val s = file.getName
    val num = s.substring(0, s.indexOf('.'))
    num.toInt
  }

  private def sourceFile: File = new File(dir, SOURCE_NAME)

  private def xmlName(number: Int): String = s"${numberString(number)}.xml"

  private def idsName(number: Int): String = s"${numberString(number)}.ids"

  private def activeIdsName(number: Int): String = s"${numberString(number)}.act"

  private def createXmlFile(number: Int): File = new File(dir, xmlName(number))

  private def createIdsFile(number: Int): File = new File(dir, idsName(number))

  private def idsFile(file: File): File = new File(file.getParentFile, idsName(getFileNumber(file)))

  private def activeIdsFile(file: File): File = new File(file.getParentFile, activeIdsName(getFileNumber(file)))

  private def createIntersectionFile(oldFile: File, newFile: File): File = new File(dir, s"${oldFile.getName}_${newFile.getName}")

  private def avoidFiles(file: File): Seq[File] = {
    val prefix = s"${idsFile(file).getName}_"
    fileList.filter(f => f.getName.startsWith(prefix))
  }

  private def avoidSet(xmlFile: File): Set[String] = {
    var idSet = new mutable.HashSet[String]()
    avoidFiles(xmlFile).foreach(Source.fromFile(_).getLines().foreach(idSet.add))
    idSet.toSet
  }

  private def xmlFiles = fileList.filter(f => f.isFile && f.getName.endsWith(".xml")).sortBy(_.getName)

  private def processFile(fileFiller: File => File) = {
    def writeToFile(file: File, string: String): Unit = {
      Some(new PrintWriter(file)).foreach {
        p =>
          p.println(string)
          p.close()
      }
    }
    val filesBefore = xmlFiles
    val fileNumber =
      filesBefore.reverse.headOption match {
        case Some(latestFile) =>
          getFileNumber(latestFile) + 1
        case None =>
          0
      }
    val files =
      if (fileNumber % MAX_FILES < MAX_FILES - 1)
        filesBefore
      else {
        moveFiles()
        xmlFiles
      }
    val file = fileFiller(createXmlFile(fileNumber))
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
      val newIdsFile = createIdsFile(fileNumber)
      FileUtils.write(newIdsFile, idSet.toList.sorted.mkString("\n") + "\n")
      writeToFile(activeIdsFile(newIdsFile), idSet.size.toString)
      var idsFiles = files.map(idsFile)
      idsFiles.foreach { idsFile =>
        if (!idsFile.exists()) throw new RuntimeException(s"where the hell is $idsFile?")
        val ids = Source.fromFile(idsFile).getLines()
        val intersectionIds = ids.filter(idSet.contains)
        if (intersectionIds.nonEmpty) {
          // create an intersection file
          val intersection = createIntersectionFile(idsFile, newIdsFile)
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

  // public things:

  def acceptFile(file: File): Option[File] = processFile { targetFile =>
    FileUtils.moveFile(file, targetFile)
    targetFile
  }

  def acceptPage(page: String): Option[File] = processFile { targetFile =>
    FileUtils.write(targetFile, page, "UTF-8")
    targetFile
  }

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

  def countFiles = fileList.size

  def getSourceFile: File = {
    if (!sourceFile.exists()) { // todo: check if it's too old as well
      val out = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(sourceFile)), "UTF-8")
      parse(rawRecord => out.write(rawRecord.text), percent => true)
      out.close()
    }
    sourceFile
  }

}
