//===========================================================================
//    Copyright 2014 Delving B.V.
//
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
import java.lang.Boolean.FALSE

import org.apache.commons.io.input.CountingInputStream
import org.apache.jena.query.{Dataset, DatasetFactory}
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.Json
import services.FileHandling.{ReadProgress, clearDir}
import services.StringHandling.createFOAFAbout
import services.{FileHandling, ProgressReporter, StringHandling, Temporal}
import triplestore.GraphProperties._

import scala.collection.JavaConversions._

object ProcessedRepo {

  val XML_SUFFIX = ".xml"
  val ERROR_SUFFIX = ".txt"
  val BULK_ACTION_SUFFIX = "_actions.txt"
  val NQUAD_SUFFIX = ".nq"
  val chunkSize = 100

  case class GraphChunk(dataset: Dataset, dsInfo: DsInfo, bulkActions: String) {}

  trait GraphReader {
    def isActive: Boolean

    def readChunkOpt: Option[GraphChunk]

    def close(): Unit
  }

  private def numberString(number: Int) = "%05d".format(number)

  private def xmlFileName(number: Int) = s"${numberString(number)}$XML_SUFFIX"

  private def errorFileName(number: Int) =
    s"${numberString(number)}$ERROR_SUFFIX"

  private def bulkActionFileName(number: Int) =
    s"${numberString(number)}$BULK_ACTION_SUFFIX"

  private def nquadFileName(number: Int) =
    s"${numberString(number)}$NQUAD_SUFFIX"

  private def getFileNumber(file: File): Int =
    file.getName.substring(0, file.getName.indexOf('.')).toInt

  case class ProcessedOutput(home: File, number: Int) {
    val xmlFile = new File(home, xmlFileName(number))
    val errorFile = new File(home, errorFileName(number))
    val bulkActionFile = new File(home, bulkActionFileName(number))
    val nquadFile = new File(home, nquadFileName(number))
  }

}

class ProcessedRepo(val home: File, dsInfo: DsInfo) {

  import dataset.ProcessedRepo._

  home.mkdir()

  // ===================

  val baseOutput = ProcessedOutput(home, 0)

  def nonEmpty: Boolean = baseOutput.xmlFile.exists()

  def listXmlFiles: List[File] =
    home
      .listFiles()
      .filter(f => f.getName.endsWith(XML_SUFFIX))
      .sortBy(_.getName)
      .toList

  def listBulkActionFiles: List[File] =
    home
      .listFiles()
      .filter(f => f.getName.endsWith(BULK_ACTION_SUFFIX))
      .sortBy(_.getName)
      .toList

  def listOutputs: List[ProcessedOutput] = {
    listXmlFiles.map(file => ProcessedOutput(home, getFileNumber(file))).toList
  }

  def listSourceFiles: List[File] = {
    val sourceHome = new File(home.getParentFile, "/source")
    sourceHome
      .listFiles()
      .filter(f => f.getName.endsWith(".zip"))
      .sortBy(_.getName)
      .toList
  }

  def createOutput: ProcessedOutput = {
    val number = listOutputs.lastOption.map(_.number + 1).getOrElse(0)
    ProcessedOutput(home, number)
  }

  def getLatestErrors: Option[File] = {
    listOutputs.filter(_.errorFile.exists()).map(_.errorFile).lastOption
  }

  def getLatestBulkActions: Option[File] = {
    listOutputs
      .filter(_.bulkActionFile.exists())
      .map(_.bulkActionFile)
      .lastOption
  }

  def getLatestNquads: Option[File] = {
    listOutputs
      .filter(_.nquadFile.exists())
      .map(_.nquadFile)
      .lastOption
  }

  def getLatestProcessed: Option[File] = {
    val files = listOutputs
    files.filter(_.xmlFile.exists()).map(_.xmlFile).lastOption
  }

  def getLatestSourced: Option[File] = {
    val sourceZips = listSourceFiles
    sourceZips.sortBy(_.lastModified).headOption
  }

  def clear() = clearDir(home)

  def createGraphReader(fileOpt: Option[File],
                        timeStamp: DateTime,
                        progressReporter: ProgressReporter) = new GraphReader {
    //var files: Seq[File] = fileOpt.map(file => Seq(file)).getOrElse(listBulkActionFiles)
    var files: Seq[File] = listBulkActionFiles
    val totalLength = (0L /: files.map(_.length()))(_ + _)
    var activeReader: Option[BufferedReader] = None
    var previousBytesRead = 0L
    var activeCounter: Option[CountingInputStream] = None
    val timeString = Temporal.timeToUTCString(timeStamp)

    def activeCount = activeCounter.map(_.getByteCount).getOrElse(0L)

    object ProcessedReadProgress extends ReadProgress {
      override def getPercentRead: Int = {
        val count = previousBytesRead + activeCount
        ((100 * count) / totalLength).toInt
      }
    }

    progressReporter.setReadProgress(ProcessedReadProgress)

    def readerOpt: Option[BufferedReader] =
      if (activeReader.isDefined) {
        activeReader
      } else {
        files.headOption.map { file =>
          files = files.tail
          val (reader, counter) = FileHandling.readerCounting(file)
          activeReader = Some(reader)
          activeCounter = Some(counter)
          activeReader.get
        }
      }

    override def isActive = readerOpt.isDefined

    override def readChunkOpt: Option[GraphChunk] = {
      val dataset = DatasetFactory.createGeneral()
      val recordText = new StringBuilder
      var graphCount = 0
      var chunkComplete = false
      //Logger.info("Start reading files.")
      while (!chunkComplete) {
        progressReporter.checkInterrupt()
        readerOpt.map { reader =>
          Option(reader.readLine()).map {
            case x: String =>
              if (!x.isEmpty) {
                graphCount += 1
                recordText.append(x).append("\n")
                if (graphCount >= chunkSize) chunkComplete = true
              }
          } getOrElse {
            reader.close()
            previousBytesRead += activeCount
            activeReader = None
            activeCounter = None
          }
        } getOrElse {
          chunkComplete = true
        }
      }
      //Logger.info(s"Graphcount is: $graphCount.")
      if (graphCount > 0) Some(GraphChunk(dataset, dsInfo, recordText.toString())) else None
    }

    override def close(): Unit = {
      previousBytesRead += activeCount
      activeReader.map(_.close())
      activeReader = None
      activeCounter = None
      files = Seq.empty[File]
    }
  }
}
