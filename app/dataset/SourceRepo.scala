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
package dataset

import java.io._
import java.nio.file.Files
import java.security.MessageDigest
import java.util.regex.Pattern
import java.util.zip.{GZIPInputStream, ZipEntry, ZipOutputStream}

import eu.delving.metadata.StringUtil
import harvest.Harvesting.HarvestType
import org.apache.commons.codec.binary.Base32
import org.apache.commons.io.input.BOMInputStream
import org.apache.commons.io.{FileUtils, IOUtils}
import org.joda.time.DateTime
import record.PocketParser
import record.PocketParser._
import services.FileHandling.{clearDir, sourceFromFile, writer}
import services.ProgressReporter

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
 * @author Gerald de Jong <gerald@delving.eu>
 */

object SourceRepo {

  val MAX_FILES = 100
  val SOURCE_FACTS_FILE = "source_facts.txt"

  object SourceFacts {
    def apply(harvestType: HarvestType): SourceFacts = SourceFacts(
      harvestType.name,
      harvestType.recordRoot,
      harvestType.uniqueId,
      harvestType.recordContainer
    )
  }


  // ~~~ play.libs.Crypto

  case class IdFilter(filterType: String, filterExpression: Option[String]) {
    val filter: String => String = filterType match {

      case "verbatim" => input =>
        StringUtil.sanitizeId(input)

      case "sha256-hash" => input =>
        try {
          val m = MessageDigest.getInstance("SHA-256")
          val out = m.digest(input.getBytes)
          val base32 = new Base32()
          val padding = "===="
          val padded = base32.encodeToString(out)
          padded.substring(0, padded.length - padding.length)
        } catch {
          case e: Exception => throw new RuntimeException(e)
        }

      case "replacement" => input =>
        val expression = filterExpression.get
        val delimiter = ":::"
        val divider = expression.indexOf(delimiter)
        if (divider < 0) throw new RuntimeException(s"Expected three-colon delimiter in [$expression]")
        val pattern = Pattern.compile(expression.substring(0, divider))
        val replacement = expression.substring(divider + delimiter.length)
        // val trimmed : String = unique.trim.replaceAll(":", "-")
        pattern.matcher(input).replaceAll(replacement)

      case _ =>
        throw new RuntimeException(s"Cannot create ID Filter of type $filterType with expression $filterExpression")
    }
  }

  val VERBATIM_FILTER = IdFilter("verbatim", None)

  case class SourceFacts(sourceType: String, recordRoot: String, uniqueId: String, recordContainer: Option[String])

  def sourceFactsFile(home: File) = new File(home, SOURCE_FACTS_FILE)

  def sourceFacts(home: File): SourceFacts = {
    val file = sourceFactsFile(home)
    val lines = Source.fromFile(file).getLines()
    val map = lines.flatMap { line =>
      val equals = line.indexOf("=")
      if (equals < 0) None else Some(line.substring(0, equals).trim -> line.substring(equals + 1).trim)
    }.toMap
    val sourceType = map.getOrElse("sourceType", throw new RuntimeException(s"Source type missing!"))
    val recordRoot = map.getOrElse("recordRoot", throw new RuntimeException(s"Record root missing!"))
    val uniqueId = map.getOrElse("uniqueId", throw new RuntimeException(s"Unique ID missing!"))
    val recordContainer = map.getOrElse("recordContainer", throw new RuntimeException(s"Record root missing!"))
    SourceFacts(sourceType, recordRoot, uniqueId, Option(recordContainer).find(_.nonEmpty))
  }

  def createClean(home: File, sourceFacts: SourceFacts): SourceRepo = {
    clearDir(home)
    val file = sourceFactsFile(home)
    val facts =
      s"""
         |sourceType=${sourceFacts.sourceType}
         |recordRoot=${sourceFacts.recordRoot}
         |uniqueId=${sourceFacts.uniqueId}
         |recordContainer=${sourceFacts.recordContainer.getOrElse("")}
       """.stripMargin
    FileUtils.write(file, facts)
    new SourceRepo(home)
  }

}

class SourceRepo(home: File) {

  import dataset.SourceRepo._

  private def numberString(number: Int): String = "%05d".format(number)

  private def newSubdirectory(dirs: Seq[File]): File = {
    val number = dirs.sortBy(_.getName).lastOption.map(_.getName.toInt + 1).getOrElse(0)
    val sub = new File(home, numberString(number))
    sub.mkdir()
    sub
  }

  private def fileList: Seq[File] = {
    val all = home.listFiles()
    val (files, dirs) = all.partition(_.isFile)
    dirs.flatMap(_.listFiles()) ++ files.filter(_.getName != SOURCE_FACTS_FILE)
  }

  private def moveFiles = {
    val all = home.listFiles()
    val (files, dirs) = all.partition(_.isFile)
    val sub = newSubdirectory(dirs)
    files.foreach(file => Files.move(file.toPath, new File(sub, file.getName).toPath))
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

  private def createZipFile(number: Int): File = new File(home, zipName(number))

  private def createIdsFile(number: Int): File = new File(home, idsName(number))

  private def createIdsFile(file: File): File = new File(file.getParentFile, idsName(getFileNumber(file)))

  private def createActiveIdsFile(file: File): File = new File(file.getParentFile, activeIdsName(getFileNumber(file)))

  private def createIntersectionFile(oldFile: File, newFile: File): File = new File(home, s"${oldFile.getName}_${newFile.getName}")

  private def avoidFiles(file: File): Seq[File] = {
    val prefix = s"${createIdsFile(file).getName}_"
    fileList.filter(f => f.getName.startsWith(prefix))
  }

  private def avoidSet(zipFile: File): Set[String] = {
    var idSet = new mutable.HashSet[String]()
    avoidFiles(zipFile).foreach(Source.fromFile(_, "UTF-8").getLines().foreach(idSet.add))
    idSet.toSet
  }

  private def listZipFiles = fileList.filter(f => f.isFile && f.getName.endsWith(".zip")).sortBy(_.getName)

  private def processFile(idFilter: IdFilter, progress: ProgressReporter, provideZipFile: File => File) = {
    def writeToFile(file: File, string: String): Unit = Some(new PrintWriter(file)).foreach { writer =>
      writer.println(string)
      writer.close()
    }
    val zipFiles = listZipFiles
    val fileNumber = zipFiles.lastOption.map(getFileNumber(_) + 1).getOrElse(0)
    val files = if (fileNumber > 0 && fileNumber % MAX_FILES == 0) moveFiles else zipFiles
    val file = provideZipFile(createZipFile(fileNumber))
    val idSet = new mutable.HashSet[String]()
    val parser = new PocketParser(sourceFacts, idFilter)
    def receiveRecord(pocket: Pocket): Unit = idSet.add(pocket.id)
    val (source, readProgress) = sourceFromFile(file)
    progress.setReadProgress(readProgress)
    try {
      parser.parse(source, Set.empty, receiveRecord, progress)
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
      val idWriter = writer(newIdsFile)
      idSet.foreach { id =>
        idWriter.write(id)
        idWriter.write('\n')
      }
      idWriter.close()
      writeToFile(createActiveIdsFile(newIdsFile), idSet.size.toString)
      val idsFiles = files.map(createIdsFile)
      idsFiles.foreach { idsFile =>
        if (!idsFile.exists()) throw new RuntimeException(s"where the hell is $idsFile?")
        val ids = Source.fromFile(idsFile, "UTF-8").getLines()
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

  lazy val sourceFacts = SourceRepo.sourceFacts(home)

  def countFiles = fileList.size

  def acceptFile(file: File, progressReporter: ProgressReporter): Option[File] = processFile(VERBATIM_FILTER, progressReporter, { targetFile =>
    val name = file.getName
    if (name.endsWith(".zip")) {
      FileUtils.moveFile(file, targetFile)
      targetFile
    }
    else if (name.endsWith(".xml")) {
      val zos = new ZipOutputStream(new FileOutputStream(targetFile))
      zos.putNextEntry(new ZipEntry(name))
      val bis = new BOMInputStream(new FileInputStream(file))
      IOUtils.copy(bis, zos)
      bis.close()
      zos.closeEntry()
      zos.close()
      targetFile
    }
    else if (name.endsWith(".xml.gz")) {
      val zos = new ZipOutputStream(new FileOutputStream(targetFile))
      zos.putNextEntry(new ZipEntry(name.replace(".xml.gz", ".xml")))
      val gis = new GZIPInputStream(new FileInputStream(file))
      val bis = new BOMInputStream(gis)
      IOUtils.copy(bis, zos)
      bis.close()
      zos.closeEntry()
      zos.close()
      targetFile
    }
    else {
      throw new RuntimeException(s"SourceRepo can only accept .zip, .xml.gz, or .xml")
    }
  })

  def parsePockets(output: Pocket => Unit, idFilter: IdFilter, progress: ProgressReporter): Int = {
    val parser = new PocketParser(sourceFacts, idFilter)
    val actFiles = fileList.filter(f => f.getName.endsWith(".act"))
    val activeIdCounts = actFiles.map(FileUtils.readFileToString).map(s => s.trim.toInt)
    val totalActiveIds = activeIdCounts.fold(0)(_ + _)
    progress.setMaximum(totalActiveIds)
    listZipFiles.foreach { zipFile =>
      progress.checkInterrupt()
      var idSet = avoidSet(zipFile)
      val (source, readProgress) = sourceFromFile(zipFile)
      // ignore this read progress because it's one of many files
      try {
        parser.parse(source, idSet.toSet, output, progress)
      }
      finally {
        source.close()
      }
    }
    totalActiveIds
  }

  def lastModified = listZipFiles.lastOption.map(_.lastModified()).getOrElse(0L)

  def generatePockets(sourceOutputStream: OutputStream, idFilter: IdFilter, progress: ProgressReporter): Int = {
    var recordCount = 0
    val rawOutput = writer(sourceOutputStream)
    try {
      val startList = s"""<$POCKET_LIST>\n"""
      val endList = s"""</$POCKET_LIST>\n"""
      rawOutput.write(startList)
      def pocketWriter(pocket: Pocket): Unit = {
        rawOutput.write(pocket.text)
      }
      recordCount = parsePockets(pocketWriter, idFilter, progress)
      rawOutput.write(endList)
    } finally {
      rawOutput.close()
    }
    recordCount
  }

  // todo: report this to the front end
  def acquisitionHistory: List[(DateTime, Int)] = {
    List.empty
  }
}
