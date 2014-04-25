package services

import play.api.libs.json._
import scala.xml.pull.XMLEventReader
import scala.io.Source
import scala.xml.pull.EvElemStart
import scala.xml.pull.EvText
import scala.xml.pull.EvElemEnd
import scala.Some
import java.io.{File, FileWriter, BufferedWriter}
import org.apache.commons.io.FileUtils

trait XRay {

  class XRayNode(val parentDirectory: File, val parent: XRayNode, val tag: String) {
    var kids = Map.empty[String, XRayNode]
    var count = 0
    var lengthHistogram = new LengthHistogram(tag)
    var directory : File = if (tag == null) parentDirectory else new File(parentDirectory, tag.replace(":", "_").replace("@", "_"))
    var valueWriter: Option[BufferedWriter] = None
    var valueBuffer = new StringBuilder
    directory.mkdirs()

    def kid(tag: String) = {
      kids.get(tag) match {
        case Some(kid) => kid
        case None =>
          val kid = new XRayNode(directory, this, tag)
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
        lengthHistogram.record(value)
        if (valueWriter == None) {
          valueWriter = Option(new BufferedWriter(new FileWriter(new File(directory, "values.txt"))))
        }
        valueWriter.map { writer =>
          writer.write(stripLines(value))
          writer.newLine()
        }
      }
    }

    def finish(): Unit = {
      valueWriter.map(_.close())
      kids.values.foreach(_.finish())
      if (parent == null) {
        val pretty = Json.prettyPrint(Json.toJson(this))
        val analysisFile = new File(directory, FileRepository.analysisFileName)
        FileUtils.writeStringToFile(analysisFile, pretty, "UTF-8")
      }
      // todo: write out the local status file too?
    }

    def path: String = {
      if (tag == null) "" else parent.path + s"/$tag"
    }

    private def addToValue(value: String) = {
      if (!valueBuffer.isEmpty) {
        valueBuffer.append(' ')
      }
      valueBuffer.append(value)
    }

    private def stripLines(value: String) = {
      val noReturn = value.replaceAll("\n", " ") // todo: refine to remove double spaces
      noReturn
    }

    private def close = {
      valueWriter.map(_.close())
    }

    override def toString = s"XRayNode($tag)"
  }

  object XRayNode {
    val STEP = 10000

    def apply(source: Source, directory:File, progress: Long => Unit): XRayNode = {
      val root = new XRayNode(directory, null, null)
      var node = root
      var count = 0L

      val events = new XMLEventReader(source)

      while (events.hasNext) {

        if (count % STEP == 0) {
          progress(count)
        }
        count += 1

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

          case EvElemEnd(pre, label) =>
            node.end()
            node = node.parent

          case x =>
            println("EVENT? "+x)
        }
      }
      root.finish()
      progress(-1)
      root
    }
  }

  case class HistogramRange(from: Int, to: Int = 0) {

    def fits(value: Int) = (to == 0 && value == from) || (value >= from && (to == 0 || value <= to))

    override def toString = {
      if (to < 0) s"$from-*"
      else if (to > 0) s"$from-$to"
      else s"$from"
    }
  }

  val lengthRanges = Seq(
    HistogramRange(0),
    HistogramRange(1),
    HistogramRange(2),
    HistogramRange(3),
    HistogramRange(4),
    HistogramRange(5),
    HistogramRange(6),
    HistogramRange(7),
    HistogramRange(8),
    HistogramRange(9),
    HistogramRange(10),
    HistogramRange(11, 20),
    HistogramRange(21, 30),
    HistogramRange(31, 60),
    HistogramRange(60, -1)
  )

  class Counter(val range: HistogramRange, var count: Int = 0)

  class LengthHistogram(val name: String) {
    val counters = lengthRanges.map(range => new Counter(range))

    def record(string: String) {
      for (counter <- counters if counter.range.fits(string.length)) {
        counter.count += 1
      }
    }
  }

  implicit val nodeWrites = new Writes[XRayNode] {

    def writes(counter: Counter): JsValue = Json.arr(counter.range.toString, counter.count.toString)

    def writes(histogram: LengthHistogram): JsValue = {
      JsArray(histogram.counters.filter(counter => counter.count > 0).map(counter => writes(counter)))
    }

    def writes(node: XRayNode) = Json.obj(
      "tag" -> node.tag,
      "path" -> node.path,
      "count" -> node.count,
      "kids" -> JsArray(node.kids.values.map(writes(_)).toSeq),
      "lengthHistogram" -> writes(node.lengthHistogram)
    )
  }
}
