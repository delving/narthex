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

import play.api.libs.json._
import scala.xml.pull._
import scala.io.Source
import java.io.{FileWriter, BufferedWriter}
import org.apache.commons.io.FileUtils
import scala.xml.pull.EvElemStart
import play.api.libs.json.JsArray
import scala.xml.pull.EvText
import scala.xml.pull.EvElemEnd
import scala.Some
import scala.collection.mutable
import scala.util.{Try, Random}
import org.apache.commons.io.input.CountingInputStream

trait XRay {

  class XRayNode(val directory: NodeRepo, val parent: XRayNode, val tag: String) {
    var kids = Map.empty[String, XRayNode]
    var count = 0
    var lengths = new LengthHistogram()
    var valueWriter: Option[BufferedWriter] = None
    var valueBuffer = new StringBuilder

    def kid(tag: String) = {
      kids.get(tag) match {
        case Some(kid) => kid
        case None =>
          val kid = new XRayNode(directory.child(tag), this, tag)
          kids += tag -> kid
          kid
      }
    }

    def start(): XRayNode = {
      count += 1
      valueBuffer.clear()
      this
    }

    def value(value: String): XRayNode = {
      val trimmed = value.trim()
      if (!trimmed.isEmpty) {
        addToValue(trimmed)
      }
      this
    }

    def end() = {
      var value = valueBuffer.toString().trim()
      if (!value.isEmpty) {
        lengths.record(value)
        if (valueWriter == None) {
          valueWriter = Option(new BufferedWriter(new FileWriter(directory.values)))
        }
        valueWriter.map {
          writer =>
            writer.write(stripLines(value))
            writer.newLine()
        }
      }
    }

    def finish(): Unit = {
      valueWriter.map(_.close())
      val index = kids.values.map(kid => Repo.tagToDirectory(kid.tag)).mkString("\n")
      FileUtils.writeStringToFile(directory.indexText, index)
      kids.values.foreach(_.finish())
    }

    def sort(sortStarter: XRayNode => Unit): Unit = {
      sortStarter(this)
      kids.values.foreach(_.sort(sortStarter))
    }

    def path: String = {
      if (tag == null) "" else parent.path + s"/$tag"
    }

    private def addToValue(value: String) = {
      if (!valueBuffer.isEmpty) {
        valueBuffer.append(' ') // todo: problem when entities are added
      }
      valueBuffer.append(value)
    }

    private def stripLines(value: String) = {
      val noReturn = value.replaceAll("\n", " ") // todo: refine to remove double spaces
      noReturn
    }

    override def toString = s"XRayNode($tag)"
  }

  object XRayNode {

    def apply(source: Source, length: Long, counter: CountingInputStream, directory: FileRepo, progress: Int => Unit): Try[XRayNode] = Try {
      val base = new XRayNode(directory.root, null, null)
      var node = base
      var percentWas = -1
      var lastProgress = 0l
      val events = new XMLEventReader(source)

      def sendProgress(): Unit = {
        val percent = ((counter.getByteCount * 100) / length).toInt
        if (percent > percentWas && (System.currentTimeMillis() - lastProgress) > 333) {
          progress(percent)
          percentWas = percent
          lastProgress = System.currentTimeMillis()
        }
      }

      while (events.hasNext) {

        events.next() match {

          case EvElemStart(pre, label, attrs, scope) =>
            node = node.kid(label).start()
            attrs.foreach {
              attr =>
                val kid = node.kid(s"@${attr.key}").start()
                kid.value(attr.value.toString())
                kid.end()
            }

          case EvText(text) =>
            node.value(text.trim)

          case EvEntityRef(entity) =>
            entity match {
              case "amp" => node.value("&")
              case "quot" => node.value("\"")
              case "lt" => node.value("<")
              case "gt" => node.value(">")
              case x => println("Entity:" + x)
            }

          case EvElemEnd(pre, label) =>
            sendProgress()
            node.end()
            node = node.parent

          case EvComment(text) =>
          // todo: unknown entity apos; // probably tell the parser to resolve or something

          case x =>
            println("EVENT? " + x) // todo: record these in an error file for later
        }
      }

      val root = base.kids.values.head
      base.finish()
      val pretty = Json.prettyPrint(Json.toJson(root))
      FileUtils.writeStringToFile(directory.index, pretty, "UTF-8")
      progress(100)
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

  implicit val nodeWrites = new Writes[XRayNode] {

    def writes(counter: Counter): JsValue = Json.arr(counter.range.toString, counter.count.toString)

    def writes(histogram: LengthHistogram): JsValue = {
      JsArray(histogram.counters.filter(counter => counter.count > 0).map(counter => writes(counter)))
    }

    def writes(sample: RandomSample): JsValue = {
      JsArray(sample.values.map(value => JsString(value)))
    }

    def writes(node: XRayNode) = Json.obj(
      "tag" -> node.tag,
      "path" -> node.path,
      "count" -> node.count,
      "lengths" -> writes(node.lengths),
      "kids" -> JsArray(node.kids.values.map(writes).toSeq)
    )
  }
}
