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

class SourceRepo(val dir: File, recordRoot: String, uniqueId: String) extends RecordHandling {

  def mkdirs = dir.mkdirs()

  def fileList: List[File] = {
    val files = dir.listFiles()
    if (files == null) List.empty[File] else files.toList
  }

  def numberString(number: Int): String = "%05d".format(number)

  def fileNumber(file: File): Int = {
    val s = file.getName
    val num = s.substring(0, s.indexOf('.'))
    num.toInt
  }

  def xmlName(number: Int): String = s"${numberString(number)}.xml"

  def idsName(number: Int): String = s"${numberString(number)}.ids"

  def activeIdsName(number: Int): String = s"${numberString(number)}.act"

  def xmlFile(number: Int): File = new File(dir, xmlName(number))

  def idsFile(number: Int): File = new File(dir, idsName(number))

  def idsFile(file: File): File = idsFile(fileNumber(file))

  def activeIdsFile(number: Int): File = new File(dir, activeIdsName(number))

  def activeIdsFile(file: File): File = activeIdsFile(fileNumber(file))

  def intersectionFile(oldFile: File, newFile: File): File = new File(dir, s"${oldFile.getName}_${newFile.getName}")
  
  def xmlFiles = fileList.filter(f => f.isFile && f.getName.endsWith(".xml")).sortBy(_.getName)

  def nextFileNumber(files: List[File]): Int = {
    files.reverse.headOption match {
      case Some(file) =>
        fileNumber(file) + 1
      case None =>
        0
    }
  }

  def nextFileNumber: Int = nextFileNumber(xmlFiles)
  
  def avoidFiles(file: File): List[File] = {
    val prefix = s"${idsFile(file).getName}_"
    fileList.filter(f => f.getName.startsWith(prefix))
  }

  def avoidSet(xmlFile: File): Set[String] = {
    var idSet = new mutable.HashSet[String]()
    avoidFiles(xmlFile).foreach(Source.fromFile(_).getLines().foreach(idSet.add))
    idSet.toSet
  }

  def handleFile(xmlFile: File, fileNumber: Int, files: List[File]) = {
    def writeToFile(file: File, string: String): Unit = {
      Some(new PrintWriter(file)).foreach {
        p =>
          p.println(string)
          p.close()
      }
    }
    var idSet = new mutable.HashSet[String]()
    val parser = new RawRecordParser(recordRoot, uniqueId)
    def sendProgress(percent: Int): Boolean = true
    def receiveRecord(record: RawRecord): Unit = idSet.add(record.id)
    def source = Source.fromFile(xmlFile)
    parser.parse(source, Set.empty, receiveRecord, -1, sendProgress)
    if (idSet.isEmpty) {
      FileUtils.deleteQuietly(xmlFile)
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
      Some(xmlFile)
    }
  }

  def acceptFile(file: File): Option[File] = {
    val files = xmlFiles
    val fileNumber = nextFileNumber(files)
    val target = xmlFile(fileNumber)
    FileUtils.moveFile(file, target)
    handleFile(target, fileNumber, files)
  }

  def acceptPage(page: String): Option[File] = {
    val files = xmlFiles
    val fileNumber = nextFileNumber(files)
    val target = xmlFile(fileNumber)
    FileUtils.write(target, page, "UTF-8")
    handleFile(target, fileNumber, files)
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

}
