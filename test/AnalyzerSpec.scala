import org.scalatest._
import play.api.Logger
import scala.collection.mutable
import scala.io.Source
import scala.xml.pull.{EvText, EvElemEnd, EvElemStart, XMLEventReader}

class AnalyzerSpec extends FlatSpec {

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

  "The parse" should "reveal hello" in {
    val xml = new XMLEventReader(Source.fromString(
      """<hello at="tr">
        |  <there it="is"/>
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
        attrs.foreach(md => 
          {
            val kid = node.kid(s"@${md.key}").occurrence()
            kid.value(md.value.toString())
          }
        )
      case EvText(text) if !text.trim.isEmpty =>
        node.value(text.trim)
      case EvElemEnd(pre, label) =>
        node = node.parent
      case _ =>
    }

    val hello: XRayNode = root.kid("hello")
    assert(hello.count == 1)
    assert(hello.kid("@at").count == 1)
    val there: XRayNode = hello.kid("there")
    assert(there.count == 3)
    assert(there.kid("@it").count == 2)
  }
}