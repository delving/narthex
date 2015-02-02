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
import services.FileHandling.ReadProgress
import services.{FileHandling, ProgressReporter}
import triplestore.GraphProperties._

import scala.collection.JavaConversions._

/**
 * @author Gerald de Jong <gerald@delving.eu>
 */

object ProcessedRepo {

  val SUFFIX = ".xml"
  val chunkSize = 20

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

}

class ProcessedRepo(val home: File, datasetUri: String) {

  import dataset.ProcessedRepo._

  home.mkdir()

  private def numberString(number: Int): String = "%05d".format(number)

  private def sourceFileName(number: Int): String = s"${numberString(number)}$SUFFIX"

  private def getFileNumber(file: File): Int = file.getName.substring(0, file.getName.indexOf('.')).toInt

  private def xmlFile(number: Int): File = new File(home, sourceFileName(number))

  // ===================

  val baseFile = xmlFile(0)

  def nonEmpty: Boolean = baseFile.exists()

  def listFiles = home.listFiles().filter(f => f.getName.endsWith(SUFFIX)).sortBy(_.getName).toSeq

  def createFile: File = {
    val fileNumber = listFiles.lastOption.map(getFileNumber(_) + 1).getOrElse(0)
    xmlFile(fileNumber)
  }

  def clear() = FileHandling.clearDir(home)

  def createGraphReader(fileOpt: Option[File], progressReporter: ProgressReporter) = new GraphReader {
    val LineId = "<!--<([^>]*)>-->".r
    var files: Seq[File] = fileOpt.map(file => Seq(file)).getOrElse(listFiles)
    val totalLength = (0L /: files.map(_.length()))(_ + _)
    var activeReader: Option[BufferedReader] = None
    var previousBytesRead = 0L
    var activeCounter: Option[CountingInputStream] = None

    def activeCount = activeCounter.map(_.getByteCount).getOrElse(0L)

    class ProcessedReadProgress extends ReadProgress {
      val count = previousBytesRead + activeCount
      override def getPercentRead: Int = ((100 * count) / totalLength).toInt
    }

    progressReporter.setReadProgress(new ProcessedReadProgress())

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
              m.read(new StringReader(recordText.toString()), null, "RDF/XML")
              m.add(m.getResource(graphName), m.getProperty(belongsTo.uri), m.getResource(datasetUri))
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
