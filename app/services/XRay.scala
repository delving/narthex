package services

import play.api.libs.json._
import scala.collection.mutable
import scala.xml.pull.XMLEventReader
import scala.io.Source
import scala.xml.pull.EvElemStart
import scala.xml.pull.EvText
import scala.xml.pull.EvElemEnd
import scala.Some

trait XRay {

  case class HistogramRange(from: Int, to: Int = 0) {

    def fits(value: Int) = (to < 0) || (to == 0 && value == from) || (value >= from && value <= to)

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
      "count" -> node.count,
      "kids" -> JsArray(node.kids.values.map(writes(_)).toSeq),
      "lengthHistogram" -> writes(node.lengthHistogram)
    )
  }

  class XRayNode(val parent: XRayNode, val tag: String) {
    var kids = Map.empty[String, XRayNode]
    var count = 0
    var lengthHistogram = new LengthHistogram(tag)

    def kid(tag: String) = {
      kids.get(tag) match {
        case Some(kid) => kid
        case None =>
          val kid = new XRayNode(this, tag)
          kids += tag -> kid
          kid
      }
    }

    def occurrence(): XRayNode = {
      count += 1
      this
    }

    def value(value: String): XRayNode = {
      lengthHistogram.record(value)
      this
    }

    override def toString = s"XRayNode($tag)"
  }

  object XRayNode {
    val STEP = 10000

    def apply(source: Source, progress: Long => Unit): XRayNode = {
      val root = new XRayNode(null, null)
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
            node = node.kid(label).occurrence()
            attrs.foreach {
              attr =>
                val kid = node.kid(s"@${attr.key}").occurrence()
                kid.value(attr.value.toString())
            }

          case EvText(text) if !text.trim.isEmpty =>
            node.value(text.trim)

          case EvElemEnd(pre, label) =>
            node = node.parent

          case _ =>
        }
      }

      progress(-1)
      root
    }
  }

}
