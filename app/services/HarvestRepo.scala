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

import play.api.Logger
import play.api.libs.Files._
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
 * @param sourceDir where do we work
 * @param recordRoot delimit records
 * @param uniqueId identify records
 *
 * @author Gerald de Jong <gerald@delving.eu>
 */

class HarvestRepo(sourceDir: File, recordRoot: String, uniqueId: String) extends RecordHandling {
  val MAX_FILES = 100

  private def numberString(number: Int): String = "%05d".format(number)

  private def newSubdirectory(dirs: Seq[File]): File = {
    val number = dirs.sortBy(_.getName).lastOption.map(_.getName.toInt + 1).getOrElse(0)
    val sub = new File(sourceDir, numberString(number))
    sub.mkdir()
    sub
  }

  private def fileList: Seq[File] = {
    val all = sourceDir.listFiles()
    val (files, dirs) = all.partition(_.isFile)
    dirs.flatMap(_.listFiles()) ++ files
  }

  private def moveFiles = {
    val all = sourceDir.listFiles()
    val (files, dirs) = all.partition(_.isFile)
    val sub = newSubdirectory(dirs)
    files.foreach(file => moveFile(file, new File(sub, file.getName), replace = false))
    Seq.empty[File]
  }

  private def getFileNumber(file: File): Int = {
    val s = file.getName
    val num = s.substring(0, s.indexOf('.'))
    num.toInt
  }

  private def xmlName(number: Int): String = s"${numberString(number)}.xml"

  private def idsName(number: Int): String = s"${numberString(number)}.ids"

  private def activeIdsName(number: Int): String = s"${numberString(number)}.act"

  private def createXmlFile(number: Int): File = new File(sourceDir, xmlName(number))

  private def createIdsFile(number: Int): File = new File(sourceDir, idsName(number))

  private def createIdsFile(file: File): File = new File(file.getParentFile, idsName(getFileNumber(file)))

  private def createActiveIdsFile(file: File): File = new File(file.getParentFile, activeIdsName(getFileNumber(file)))

  private def createIntersectionFile(oldFile: File, newFile: File): File = new File(sourceDir, s"${oldFile.getName}_${newFile.getName}")

  private def avoidFiles(file: File): Seq[File] = {
    val prefix = s"${createIdsFile(file).getName}_"
    fileList.filter(f => f.getName.startsWith(prefix))
  }

  private def avoidSet(xmlFile: File): Set[String] = {
    var idSet = new mutable.HashSet[String]()
    avoidFiles(xmlFile).foreach(Source.fromFile(_).getLines().foreach(idSet.add))
    idSet.toSet
  }

  private def listXmlFiles = fileList.filter(f => f.isFile && f.getName.endsWith(".xml")).sortBy(_.getName)

  private def processFile(progress: Int => Boolean, fillFile: File => File) = {
    def writeToFile(file: File, string: String): Unit = Some(new PrintWriter(file)).foreach { writer =>
      writer.println(string)
      writer.close()
    }
    val xmlFiles = listXmlFiles
    val fileNumber = xmlFiles.lastOption.map(getFileNumber(_) + 1).getOrElse(0)
    val files = if (fileNumber > 0 && fileNumber % MAX_FILES == 0) moveFiles else xmlFiles
    val file = fillFile(createXmlFile(fileNumber))
    val idSet = new mutable.HashSet[String]()
    val parser = new RawRecordParser(recordRoot, uniqueId)
    def receiveRecord(record: RawRecord): Unit = idSet.add(record.id)
    val (source, readProgress) = FileHandling.sourceFromFile(file)
    try {
      parser.parse(source, Set.empty, receiveRecord, progress, readProgress = Some(readProgress))
    }
    finally {
      source.close()
    }
    if (idSet.isEmpty) {
      file.delete()
      None
    }
    else {
      val newIdsFile = createIdsFile(fileNumber)
      writeFile(newIdsFile, idSet.toList.sorted.mkString("", "\n", "\n"))
      writeToFile(createActiveIdsFile(newIdsFile), idSet.size.toString)
      val idsFiles = files.map(createIdsFile)
      idsFiles.foreach { idsFile =>
        if (!idsFile.exists()) throw new RuntimeException(s"where the hell is $idsFile?")
        val ids = Source.fromFile(idsFile).getLines()
        val intersectionIds = ids.filter(idSet.contains)
        if (intersectionIds.nonEmpty) {
          // create an intersection file
          val intersection = createIntersectionFile(idsFile, newIdsFile)
          writeToFile(intersection, intersectionIds.mkString("\n"))
          // update the active count
          val avoid = avoidSet(idsFile)
          val activeCount = ids.count(!avoid.contains(_))
          writeToFile(createActiveIdsFile(idsFile), activeCount.toString)
        }
      }
      Some(fileNumber)
    }
  }

  // public things:

  def countFiles = fileList.size

  def acceptPage(page: String): Option[Int] = processFile(percent => true, { targetFile =>
    writeFile(targetFile, page)
    targetFile
  })

  def parse(output: RawRecord => Unit, sendProgress: Int => Boolean): Map[String, String] = {
    val parser = new RawRecordParser(recordRoot, uniqueId)
    val actFiles = fileList.filter(f => f.getName.endsWith(".act"))
    val activeIdCounts = actFiles.map(readFile).map(s => s.trim.toInt)
    val totalActiveIds = activeIdCounts.fold(0)(_ + _)
    listXmlFiles.foreach { xmlFile =>
      var idSet = avoidSet(xmlFile)
      val source = Source.fromFile(xmlFile)
      parser.parse(source, idSet.toSet, output, sendProgress, totalRecords = Some(totalActiveIds))
      source.close()
    }
    parser.namespaceMap
  }

  def lastModified = listXmlFiles.lastOption.map(_.lastModified()).getOrElse(0L)

  def generateSourceFile(sourceFile: File, progress: Int => Boolean, setNamespaceMap: Map[String,String] => Unit): Int = {
    Logger.info(s"Generating source from $sourceDir to $sourceFile")
    var recordCount = 0
    val out = new OutputStreamWriter(new FileOutputStream(sourceFile), "UTF-8")
    out.write("<?xml version='1.0' encoding='UTF-8'?>\n")
    out.write( s"""<$RECORD_LIST_CONTAINER>\n""")
    def writer(rawRecord: RawRecord) = {
      recordCount += 1
      out.write(rawRecord.text)
      if (recordCount % 1000 == 0) {
        Logger.info(s"Generating record $recordCount")
      }
    }
    val namespaceMap = parse(writer, progress)
    out.write( s"""</$RECORD_LIST_CONTAINER>\n""")
    out.close()
    setNamespaceMap(namespaceMap)
    Logger.info(s"Finished generating source from $sourceDir")
    recordCount
  }

}
