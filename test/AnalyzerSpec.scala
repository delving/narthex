import org.scalatest._
import play.api.libs.json.{JsString, JsArray, Json, Writes}
import play.api.Logger
import scala.collection.mutable
import scala.io.Source
import scala.xml.pull.{EvText, EvElemEnd, EvElemStart, XMLEventReader}

trait Nodey {

  implicit val nodeWrites = new Writes[XRayNode] {
    def writes(node: XRayNode) = Json.obj(
      "tag" -> node.tag,
      "count" -> node.count,
      "kids" -> JsArray(node.kids.values.map(writes(_)).toSeq),
      "values" -> JsArray(node.values.map(JsString(_)).toSeq)
    )
  }

  class XRayNode(val parent: XRayNode, val tag: String) {
    var kids = Map.empty[String, XRayNode]
    var count = 0
    var values = mutable.HashSet[String]()

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
      values.add(value)
      this
    }
  }
}

class AnalyzerSpec extends FlatSpec with Nodey {

  "The parse" should "reveal hello" in {
    val xml = new XMLEventReader(Source.fromString(
      """<hello at="tr">
        |  <there it="is" was="not"/>
        |  <there>was</there>
        |  <there it="isn't"/>
        |</hello>
      """.stripMargin)
    )

    val root = new XRayNode(null, null)

    var node = root

    xml.foreach {
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

    val hello = root.kid("hello")
    assert(hello.count == 1)
    assert(hello.kid("@at").count == 1)
    val there = hello.kid("there")
    assert(there.count == 3)
    assert(there.kid("@it").count == 2)
    assert(there.kid("@was").count == 1)

    println(Json.prettyPrint(Json.toJson(hello)))
  }
}