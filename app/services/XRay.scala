package services

import play.api.libs.json.{JsString, JsArray, Json, Writes}
import scala.collection.mutable
import scala.xml.pull.{EvElemEnd, EvText, EvElemStart, XMLEventReader}
import scala.io.Source

trait XRay {

  implicit val nodeWrites = new Writes[XRayNode] {
    def writes(node: XRayNode) = Json.obj(
      "tag" -> node.tag,
      "count" -> node.count,
      "kids" -> JsArray(node.kids.values.map(writes(_)).toSeq),
      "values" -> JsArray(node.values.map(JsString).toSeq)
    )
  }

  class XRayNode(val parent: XRayNode, val tag: String) {
    var kids = Map.empty[String, XRayNode]
    var count = 0
    var values = mutable.HashSet[String]()
    var unique = true

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
      if (unique && values.contains(value)) unique = false
      values.add(value)
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

      var events = new XMLEventReader(source)

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
