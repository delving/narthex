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
import java.lang.Boolean.FALSE

import com.hp.hpl.jena.query.{Dataset, DatasetFactory}
import org.apache.commons.io.input.CountingInputStream
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import services.FileHandling.{ReadProgress, clearDir}
import services.{FileHandling, ProgressReporter}
import triplestore.GraphProperties._

import scala.collection.JavaConversions._

/**
 * @author Gerald de Jong <gerald@delving.eu>
 */

object ProcessedRepo {

  val XML_SUFFIX = ".xml"
  val ERROR_SUFFIX = ".txt"
  val chunkSize = 1000

  case class GraphChunk(dataset: Dataset) {

    def sparqlUpdateGraph(dataset: Dataset, graphUri: String) = {
      val model = dataset.getNamedModel(graphUri)
      val triples = new StringWriter()
      RDFDataMgr.write(triples, model, RDFFormat.NTRIPLES_UTF8)
      s"""
        |DROP SILENT GRAPH <$graphUri>;
        |INSERT DATA { GRAPH <$graphUri> {
        |$triples}};
       """.stripMargin.trim
    }

    def toSparqlUpdate: String = dataset.listNames().toList.map(g => sparqlUpdateGraph(dataset, g)).mkString("\n")
  }

  trait GraphReader {
    def isActive: Boolean

    def readChunk: Option[GraphChunk]

    def close(): Unit
  }

  private def numberString(number: Int) = "%05d".format(number)

  private def xmlFileName(number: Int) = s"${numberString(number)}$XML_SUFFIX"

  private def errorFileName(number: Int) = s"${numberString(number)}$ERROR_SUFFIX"

  private def getFileNumber(file: File): Int = file.getName.substring(0, file.getName.indexOf('.')).toInt

  case class ProcessedOutput(home: File, number: Int) {
    val xmlFile = new File(home, xmlFileName(number))
    val errorFile = new File(home, errorFileName(number))
  }

}

class ProcessedRepo(val home: File, dsInfo: DsInfo) {

  import dataset.ProcessedRepo._

  home.mkdir()

  // ===================

  val baseOutput = ProcessedOutput(home, 0)

  def nonEmpty: Boolean = baseOutput.xmlFile.exists()

  def listXmlFiles: List[File] = home.listFiles().filter(f => f.getName.endsWith(XML_SUFFIX)).sortBy(_.getName).toList

  def listOutputs: List[ProcessedOutput] = {
    listXmlFiles.map(file => ProcessedOutput(home, getFileNumber(file))).toList
  }

  def createOutput: ProcessedOutput = {
    val number = listOutputs.lastOption.map(_.number + 1).getOrElse(0)
    ProcessedOutput(home, number)
  }

  def getLatestErrors: Option[File] = {
    listOutputs.filter(_.errorFile.exists()).map(_.errorFile).lastOption
  }

  def clear() = clearDir(home)

  def createGraphReader(fileOpt: Option[File], progressReporter: ProgressReporter) = new GraphReader {
    val LineId = "<!--<([^>]*)>-->".r
    var files: Seq[File] = fileOpt.map(file => Seq(file)).getOrElse(listXmlFiles)
    val totalLength = (0L /: files.map(_.length()))(_ + _)
    var activeReader: Option[BufferedReader] = None
    var previousBytesRead = 0L
    var activeCounter: Option[CountingInputStream] = None

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
      }
      else {
        files.headOption.map { file =>
          files = files.tail
          val (reader, counter) = FileHandling.readerCounting(file)
          activeReader = Some(reader)
          activeCounter = Some(counter)
          activeReader.get
        }
      }

    override def isActive = readerOpt.isDefined

    override def readChunk: Option[GraphChunk] = {
      val dataset = DatasetFactory.createMem()
      val recordText = new StringBuilder
      var graphCount = 0
      var chunkComplete = false
      while (!chunkComplete && progressReporter.keepReading(-1)) {
        readerOpt.map { reader =>
          Option(reader.readLine()).map {
            case LineId(graphName) =>
              val m = dataset.getNamedModel(graphName)
              try {
                m.read(new StringReader(recordText.toString()), null, "RDF/XML")
              }
              catch {
                case e: Throwable =>
                  println(recordText.toString())
                  throw e
              }
              m.add(m.getResource(graphName), m.getProperty(belongsTo.uri), m.getResource(dsInfo.uri))
              m.add(m.getResource(graphName), m.getProperty(synced.uri), m.createTypedLiteral(FALSE))
              graphCount += 1
              recordText.clear()
              if (graphCount >= chunkSize) chunkComplete = true
            case x: String =>
              recordText.append(x).append("\n")
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
      if (graphCount > 0) Some(GraphChunk(dataset)) else None
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
