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

  def apply(source: Source, processed: Boolean, datasetContext: DatasetContext, progressReporter: ProgressReporter): TreeNode = {
    val base = new TreeNode(datasetContext.treeRoot, null, null, null)
    var node = base
    val events = new XMLEventReader(source)
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
            node = node.kid(tag(pre, label), getNamespace(pre, scope) + label).start()
            attrs.foreach { attr =>
              val uri = MetaData.getUniversalKey(attr, scope)
              val kid = node.kid(s"@${attr.prefixedKey}", uri)
              kid.start().value(attr.value.toString()).end()
            }

          case EvText(text) =>
            node.value(text)

          case EvEntityRef(entity) => node.value(translateEntity(entity))

          case EvElemEnd(pre, label) =>
            node.end()
            node = node.parent

          case EvComment(text) =>
            stupidParser(text, string => node.value(translateEntity(string)))

          case EvProcInstr(target, text) =>
          // do nothing

          case x =>
            Logger.error("EVENT? " + x) // todo: record these in an error file for later
        }
      }
    }
    finally {
      events.stop()
    }
    progressReporter.checkInterrupt()
    val root = base.kids.values.head
    base.finish()
    val pretty = Json.prettyPrint(Json.toJson(root))
    FileUtils.writeStringToFile(datasetContext.index, pretty, "UTF-8")
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
      if (queue.size > size) queue.dequeue()
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

    override def writes(node: TreeNode) = Json.obj(
      "tag" -> node.tag,
      "path" -> node.path,
      "count" -> node.count,
      "lengths" -> writes(node.lengths),
      "kids" -> JsArray(node.kids.values.map(writes).toSeq)
    )
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
  val MAX_LIST = 10000
  var kids = Map.empty[String, TreeNode]
  var count = 0
  var lengths = new LengthHistogram()
  var valueBuilder = new StringBuilder
  var valueListSize = 0
  var valueList = List.empty[String]

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
    var value = crunchWhitespace(valueBuilder.toString())
    if (!value.isEmpty) {
      lengths.record(value)
      valueList = value :: valueList
      valueListSize += 1
      if (valueListSize >= MAX_LIST) flush()
    }
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