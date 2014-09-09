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

import java.io.{BufferedWriter, FileWriter}

import org.apache.commons.io.FileUtils
import play.api.libs.json.{JsArray, _}
import services.FileHandling.ReadProgress

import scala.collection.mutable
import scala.io.Source
import scala.util.{Random, Try}
import scala.xml.pull._

trait TreeHandling {

  class TreeNode(val nodeRepo: NodeRepo, val parent: TreeNode, val tag: String) {
    val MAX_LIST = 10000
    var kids = Map.empty[String, TreeNode]
    var count = 0
    var lengths = new LengthHistogram()
    var valueBuilder = new StringBuilder
    var valueListSize = 0
    var valueList = List.empty[String]
    
    def flush() = {
      val writer = new BufferedWriter(new FileWriter(nodeRepo.values, true))
      valueList.foreach{
        line =>
          writer.write(line)
          writer.newLine()
      }
      valueList = List.empty[String]
      valueListSize = 0
      writer.close()
    }

    def kid(tag: String) = {
      kids.get(tag) match {
        case Some(kid) => kid
        case None =>
          val kid = new TreeNode(nodeRepo.child(tag), this, tag)
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
      var value = FileHandling.crunchWhitespace(valueBuilder.toString())
      if (!value.isEmpty) {
        lengths.record(value)
        valueList = value :: valueList
        valueListSize += 1
        if (valueListSize >= MAX_LIST) flush()
      }
    }

    def finish(): Unit = {
      flush()
      val index = kids.values.map(kid => RepoUtil.tagToDirectory(kid.tag)).mkString("\n")
      FileUtils.writeStringToFile(nodeRepo.indexText, index)
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

  object TreeNode {

    def apply(source: Source, length: Long, readProgress: ReadProgress, directory: DatasetRepo, progress: Int => Unit): Try[TreeNode] = Try {
      val base = new TreeNode(directory.root, null, null)
      var node = base
      var percentWas = -1
      var lastProgress = 0l
      val events = new XMLEventReader(source)

      def sendProgress(): Unit = {
        val percentZero = readProgress.getPercentRead
        val percent = if (percentZero == 0) 1 else percentZero
        if (percent > percentWas && (System.currentTimeMillis() - lastProgress) > 333) {
          progress(percent)
          percentWas = percent
          lastProgress = System.currentTimeMillis()
        }
      }

      while (events.hasNext) {

        events.next() match {

          case EvElemStart(pre, label, attrs, scope) =>
            node = node.kid(FileHandling.tag(pre, label)).start()
            attrs.foreach {
              attr =>
                val kid = node.kid(s"@${attr.prefixedKey}").start()
                kid.value(attr.value.toString())
                kid.end()
            }

          case EvText(text) =>
            node.value(text)

          case EvEntityRef(entity) => node.value(FileHandling.translateEntity(entity))

          case EvElemEnd(pre, label) =>
            sendProgress()
            node.end()
            node = node.parent

          case EvComment(text) =>
            FileHandling.stupidParser(text, string => node.value(FileHandling.translateEntity(string)))

          case x =>
            println("EVENT? " + x) // todo: record these in an error file for later
        }
      }

      val root = base.kids.values.head
      base.finish()
      val pretty = Json.prettyPrint(Json.toJson(root))
      FileUtils.writeStringToFile(directory.index, pretty, "UTF-8")
      root
    }
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

    def isEmpty = counters.filter(_.count > 0).isEmpty
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

    def writes(node: TreeNode) = Json.obj(
      "tag" -> node.tag,
      "path" -> node.path,
      "count" -> node.count,
      "lengths" -> writes(node.lengths),
      "kids" -> JsArray(node.kids.values.map(writes).toSeq)
    )
  }
}
