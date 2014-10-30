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
package harvest

import java.io._

import harvest.Harvesting.HarvestType
import org.apache.commons.io.FileUtils
import play.api.Logger
import play.api.libs.Files._
import record.RecordHandling
import record.RecordHandling.{Pocket, _}
import services.{FileHandling, ProgressReporter}

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
 * @param harvestType the type of harvest
 *
 * @author Gerald de Jong <gerald@delving.eu>
 */

class HarvestRepo(sourceDir: File, harvestType: HarvestType) extends RecordHandling {
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

  private def zipName(number: Int): String = s"${numberString(number)}.zip"

  private def idsName(number: Int): String = s"${numberString(number)}.ids"

  private def activeIdsName(number: Int): String = s"${numberString(number)}.act"

  private def createZipFile(number: Int): File = new File(sourceDir, zipName(number))

  private def createIdsFile(number: Int): File = new File(sourceDir, idsName(number))

  private def createIdsFile(file: File): File = new File(file.getParentFile, idsName(getFileNumber(file)))

  private def createActiveIdsFile(file: File): File = new File(file.getParentFile, activeIdsName(getFileNumber(file)))

  private def createIntersectionFile(oldFile: File, newFile: File): File = new File(sourceDir, s"${oldFile.getName}_${newFile.getName}")

  private def avoidFiles(file: File): Seq[File] = {
    val prefix = s"${createIdsFile(file).getName}_"
    fileList.filter(f => f.getName.startsWith(prefix))
  }

  private def avoidSet(zipFile: File): Set[String] = {
    var idSet = new mutable.HashSet[String]()
    avoidFiles(zipFile).foreach(Source.fromFile(_).getLines().foreach(idSet.add))
    idSet.toSet
  }

  private def listZipFiles = fileList.filter(f => f.isFile && f.getName.endsWith(".zip")).sortBy(_.getName)

  private def processFile(progressReporter: ProgressReporter, fillFile: File => File) = {
    def writeToFile(file: File, string: String): Unit = Some(new PrintWriter(file)).foreach { writer =>
      writer.println(string)
      writer.close()
    }
    val zipFiles = listZipFiles
    val fileNumber = zipFiles.lastOption.map(getFileNumber(_) + 1).getOrElse(0)
    val files = if (fileNumber > 0 && fileNumber % MAX_FILES == 0) moveFiles else zipFiles
    val file = fillFile(createZipFile(fileNumber))
    val idSet = new mutable.HashSet[String]()
    val parser = new RawRecordParser(harvestType.recordRoot, harvestType.uniqueId, harvestType.deepRecordContainer)
    def receiveRecord(record: Pocket): Unit = idSet.add(record.id)
    val (source, readProgress) = FileHandling.sourceFromFile(file)
    progressReporter.setReadProgress(readProgress)
    try {
      parser.parse(source, Set.empty, receiveRecord, progressReporter)
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
      Some(file)
    }
  }

  // public things:

  def countFiles = fileList.size

  def acceptZipFile(file: File, progressReporter: ProgressReporter): Option[File] = processFile(progressReporter, { targetFile =>
    if (!file.getName.endsWith(".zip")) throw new RuntimeException(s"Requires zip file ${file.getName}")
    FileUtils.moveFile(file, targetFile)
    targetFile
  })

  def parse(output: Pocket => Unit, progressReporter: ProgressReporter): Map[String, String] = {
    val parser = new RawRecordParser(harvestType.recordRoot, harvestType.uniqueId, harvestType.deepRecordContainer)
    val actFiles = fileList.filter(f => f.getName.endsWith(".act"))
    val activeIdCounts = actFiles.map(readFile).map(s => s.trim.toInt)
    val totalActiveIds = activeIdCounts.fold(0)(_ + _)
    progressReporter.setMaximum(totalActiveIds)
    listZipFiles.foreach { zipFile =>
      var idSet = avoidSet(zipFile)
      val (source, readProgress) = FileHandling.sourceFromFile(zipFile)
      // ignore this read progress because it's one of many files
      parser.parse(source, idSet.toSet, output, progressReporter)
      source.close()
    }
    parser.namespaceMap
  }

  def lastModified = listZipFiles.lastOption.map(_.lastModified()).getOrElse(0L)

  def generateSourceFile(sourceFile: File, setNamespaceMap: Map[String,String] => Unit, progressReporter: ProgressReporter): Int = {
    Logger.info(s"Generating source from $sourceDir to $sourceFile using $harvestType")
    var recordCount = 0
    val out = new OutputStreamWriter(new FileOutputStream(sourceFile), "UTF-8")
    out.write("<?xml version='1.0' encoding='UTF-8'?>\n")
    out.write( s"""<$POCKET_LIST>\n""")
    def pocketWriter(pocket: Pocket) = {
      recordCount += 1
      out.write(pocket.text)
      if (recordCount % 1000 == 0) {
        Logger.info(s"Generating record $recordCount")
      }
    }
    val namespaceMap = parse(pocketWriter, progressReporter)
    out.write( s"""</$POCKET_LIST>\n""")
    out.close()
    setNamespaceMap(namespaceMap)
    Logger.info(s"Finished generating source from $sourceDir")
    recordCount
  }

}
