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
import analysis.TreeNode.LengthHistogram
import dataset.DatasetContext
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FileUtils.writeStringToFile
import play.api.Logger
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._
import services.FileHandling.appender
import services.StringHandling._
import services.ProgressReporter

import scala.collection.mutable
import scala.io.Source
import scala.util.Random
import scala.xml.pull._
import scala.xml.{MetaData, NamespaceBinding}

object TreeNode {

  private val logger = Logger(getClass)

  import analysis.Analyzer.AnalysisType
  import analysis.Analyzer.AnalysisType._

  /**
   * Quality statistics for a field, calculated after analysis.
   * @param totalRecords Total number of records in the dataset
   * @param recordsWithValue Number of records that have at least one non-empty value
   * @param emptyCount Number of empty or whitespace-only values
   * @param totalValues Total number of values (same as count)
   */
  case class QualityStats(
    totalRecords: Int,
    recordsWithValue: Int,
    emptyCount: Int,
    totalValues: Int
  ) {
    def completeness: Double = if (totalRecords > 0) (recordsWithValue.toDouble / totalRecords) * 100 else 0.0
    def avgPerRecord: Double = if (totalRecords > 0) totalValues.toDouble / totalRecords else 0.0
    def emptyRate: Double = if (totalValues + emptyCount > 0) (emptyCount.toDouble / (totalValues + emptyCount)) * 100 else 0.0
  }

  /**
   * Tracks field presence within a single record for completeness calculation.
   */
  class RecordTracker {
    private var fieldsWithValue = Set.empty[String]

    def markFieldPresent(path: String): Unit = {
      fieldsWithValue += path
    }

    def getFieldsWithValue: Set[String] = fieldsWithValue

    def reset(): Unit = {
      fieldsWithValue = Set.empty
    }
  }

  def apply(source: Source, analysisType: AnalysisType,
            datasetContext: DatasetContext,
            progressReporter: ProgressReporter,
            customTreeRoot: Option[NodeRepo] = None,
            customIndexFile: Option[File] = None): TreeNode = {
    val actualTreeRoot = customTreeRoot.getOrElse(datasetContext.treeRoot)
    val actualIndexFile = customIndexFile.getOrElse(datasetContext.index)
    val base = new TreeNode(actualTreeRoot, null, null, null)
    var node = base
    val events = new XMLEventReader(source)

    // Quality statistics tracking
    val recordTracker = new RecordTracker()
    var recordRootPath: Option[String] = None
    var totalRecords = 0
    var currentDepth = 0
    var recordRootDepth = -1
    val recordsWithValueCount = mutable.Map.empty[String, Int]

    def getNamespace(pre: String, scope: NamespaceBinding) = {
      val uri = scope.getURI(pre)
      if (uri == null && pre != null) throw new Exception( s"""No namespace declared for "$pre" prefix!""")
      uri
    }

    try {
      while (events.hasNext) {

        progressReporter.sendValue()

        events.next() match {

          case EvElemStart(pre, label, attrs, scope) =>
            currentDepth += 1
            node = node.kid(tag(pre, label), getNamespace(pre, scope) + label).start()

            // Detect record root: first element at depth 2 (child of document root)
            // For source: /pockets/pocket, for processed: /rdf:RDF/rdf:Description
            if (currentDepth == 2 && recordRootPath.isEmpty) {
              recordRootPath = Some(node.path)
              recordRootDepth = currentDepth
            }

            // Track record start
            if (recordRootPath.contains(node.path)) {
              totalRecords += 1
              recordTracker.reset()
            }

            attrs.foreach { attr =>
              val uri = MetaData.getUniversalKey(attr, scope)
              val kid = node.kid(s"@${attr.prefixedKey}", uri)
              kid.start().value(attr.value.toString()).end()
              // Mark attribute field as present if it has a value
              if (attr.value.toString().trim.nonEmpty) {
                recordTracker.markFieldPresent(kid.path)
              }
            }

          case EvText(text) =>
            node.value(text)

          case EvEntityRef(entity) => node.value(translateEntity(entity))

          case EvElemEnd(pre, label) =>
            // Mark field as present if it has a non-empty value
            if (node.valueBuilder.toString().trim.nonEmpty) {
              recordTracker.markFieldPresent(node.path)
            }

            node.end()

            // Track record end - update recordsWithValue counts
            if (recordRootPath.contains(node.path)) {
              recordTracker.getFieldsWithValue.foreach { fieldPath =>
                recordsWithValueCount(fieldPath) = recordsWithValueCount.getOrElse(fieldPath, 0) + 1
              }
            }

            node = node.parent
            currentDepth -= 1

          case EvComment(text) =>
            val _ = stupidParser(text, string => node.value(translateEntity(string)))

          case EvProcInstr(target, text) =>
          // do nothing

          case x =>
            logger.error("EVENT? " + x) // todo: record these in an error file for later
        }
      }
    }
    finally {
      events.stop()
    }
    progressReporter.checkInterrupt()
    val root = base.kids.values.head

    // Set quality statistics on all nodes
    root.setQualityStats(totalRecords, recordsWithValueCount.toMap)

    base.finish()
    val pretty = Json.prettyPrint(Json.toJson(root))
    FileUtils.writeStringToFile(actualIndexFile, pretty, "UTF-8")
    root
  }


  case class LengthRange(from: Int, to: Int = 0) {

    def fits(value: Int) = (to == 0 && value == from) || (value >= from && (to < 0 || value <= to))

    override def toString = {
      if (to < 0) s"$from-*"
      else if (to > 0) s"$from-$to"
      else s"$from"
    }
  }

  val lengthRanges = Seq(
    LengthRange(0),
    LengthRange(1),
    LengthRange(2),
    LengthRange(3),
    LengthRange(4),
    LengthRange(5),
    LengthRange(6, 10),
    LengthRange(11, 15),
    LengthRange(16, 20),
    LengthRange(21, 30),
    LengthRange(31, 50),
    LengthRange(50, 100),
    LengthRange(100, -1)
  )

  class Counter(val range: LengthRange, var count: Int = 0)

  class LengthHistogram() {
    val counters = lengthRanges.map(range => new Counter(range))

    def record(string: String): Unit = {
      for (counter <- counters if counter.range.fits(string.length)) {
        counter.count += 1
      }
    }

    def isEmpty = !counters.exists(_.count > 0)
  }

  class RandomSample(val size: Int, random: Random = new Random()) {
    val queue = new mutable.PriorityQueue[(Int, String)]()

    def record(string: String): Unit = {
      val randomIn: Int = random.nextInt()
      queue += (randomIn -> string)
      if (queue.size > size) { val _ = queue.dequeue() }
    }

    def values: List[String] = queue.map(pair => pair._2).toList.sorted.distinct
  }

  implicit val nodeWrites = new Writes[TreeNode] {

    def writes(counter: Counter): JsValue = Json.arr(counter.range.toString, counter.count.toString)

    def writes(histogram: LengthHistogram): JsValue = {
      JsArray(histogram.counters.filter(counter => counter.count > 0).map(counter => writes(counter)))
    }

    def writes(sample: RandomSample): JsValue = {
      JsArray(sample.values.map(value => JsString(value)))
    }

    def writes(stats: QualityStats): JsValue = Json.obj(
      "totalRecords" -> stats.totalRecords,
      "recordsWithValue" -> stats.recordsWithValue,
      "emptyCount" -> stats.emptyCount,
      "completeness" -> BigDecimal(stats.completeness).setScale(1, BigDecimal.RoundingMode.HALF_UP),
      "avgPerRecord" -> BigDecimal(stats.avgPerRecord).setScale(2, BigDecimal.RoundingMode.HALF_UP),
      "emptyRate" -> BigDecimal(stats.emptyRate).setScale(1, BigDecimal.RoundingMode.HALF_UP)
    )

    override def writes(node: TreeNode) = {
      val baseObj = Json.obj(
        "tag" -> node.tag,
        "path" -> node.path,
        "count" -> node.count,
        "lengths" -> writes(node.lengths),
        "kids" -> JsArray(node.kids.values.map(writes).toSeq)
      )
      // Add quality stats if available
      node.qualityStats match {
        case Some(stats) => baseObj + ("quality" -> writes(stats))
        case None => baseObj
      }
    }
  }

  case class ReadTreeNode(tag: String, path: String, count: Int, lengths: Seq[Seq[String]], kids: Seq[ReadTreeNode])

  implicit val nodeReads: Reads[ReadTreeNode] = (
    (JsPath \ "tag").read[String] and
      (JsPath \ "path").read[String] and
      (JsPath \ "count").read[Int] and
      (JsPath \ "lengths").read[Seq[Seq[String]]] and
      (JsPath \ "kids").lazyRead(Reads.seq[ReadTreeNode](nodeReads))
    )(ReadTreeNode)

  case class PathNode(path: String, count: Int)

  def gatherPaths(node: ReadTreeNode, requestUrl: String): List[PathNode] = {
    val list = node.kids.flatMap(n => gatherPaths(n, requestUrl)).toList
    if (node.lengths.nonEmpty && node.count > 1) {
      val path = pathToDirectory(node.path)
      PathNode(s"$requestUrl/histogram$path", node.count) :: list
    }
    else {
      list
    }
  }

  implicit val pathWrites: Writes[PathNode] = (
    (JsPath \ "path").write[String] and
      (JsPath \ "count").write[Int]
    )(unlift(PathNode.unapply))

}


class TreeNode(val nodeRepo: NodeRepo, val parent: TreeNode, val tag: String, val uri: String) {
  import TreeNode.QualityStats

  val MAX_LIST = 10000
  var kids = Map.empty[String, TreeNode]
  var count = 0
  var emptyCount = 0  // Track empty/whitespace-only values
  var lengths = new LengthHistogram()
  var valueBuilder = new StringBuilder
  var valueListSize = 0
  var valueList = List.empty[String]
  var qualityStats: Option[QualityStats] = None

  def flush() = {
    val writer = appender(nodeRepo.values)
    valueList.foreach {
      line =>
        writer.write(line)
        writer.newLine()
    }
    valueList = List.empty[String]
    valueListSize = 0
    writer.close()
  }

  def kid(tag: String, uri: String) = {
    kids.get(tag) match {
      case Some(kid) => kid
      case None =>
        val kid = new TreeNode(nodeRepo.child(tag), this, tag, uri)
        kids += tag -> kid
        kid
    }
  }

  def start(): TreeNode = {
    count += 1
    valueBuilder.clear()
    this
  }

  def value(value: String): TreeNode = {
    valueBuilder.append(value)
    this
  }

  def end() = {
    val rawValue = valueBuilder.toString()
    val value = crunchWhitespace(rawValue, None)
    // Track empty values (whitespace-only counts as empty)
    if (rawValue.nonEmpty && value.isEmpty) {
      emptyCount += 1
    }
    // todo add fix in here for linefeed replacing
    if (!value.isEmpty) {
      lengths.record(value)
      valueList = value :: valueList
      valueListSize += 1
      if (valueListSize >= MAX_LIST) flush()
    }
  }

  /**
   * Set quality statistics for this node and all children.
   * Called after parsing is complete.
   */
  def setQualityStats(totalRecords: Int, recordsWithValueCount: Map[String, Int]): Unit = {
    val recordsWithValue = recordsWithValueCount.getOrElse(path, 0)
    qualityStats = Some(QualityStats(
      totalRecords = totalRecords,
      recordsWithValue = recordsWithValue,
      emptyCount = emptyCount,
      totalValues = count
    ))
    kids.values.foreach(_.setQualityStats(totalRecords, recordsWithValueCount))
  }

  def finish(): Unit = {
    flush()
    writeStringToFile(nodeRepo.uriText, uri)
    kids.values.foreach(_.finish())
  }

  def launchSorters(sortStarter: TreeNode => Unit): Unit = {
    sortStarter(this)
    kids.values.foreach(_.launchSorters(sortStarter))
  }

  def path: String = {
    if (tag == null) "" else parent.path + s"/$tag"
  }

  override def toString = s"TreeNode($tag)"
}
