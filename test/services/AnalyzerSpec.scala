package services

import org.scalatest._
import play.api.libs.json.{JsValue, Json}
import scala.io.Source
import java.io.File

class AnalyzerSpec extends FlatSpec with XRay {

  val source = Source.fromURL(getClass.getResource("/analyze-this.xml"))

  "The parse" should "reveal hello" in {

    def progress(elementCount: Long) {
      println(elementCount)
    }

    val directory = new File("/tmp/AnalyzerSpec")
    directory.mkdir()
    val root = XRayNode(source, directory, progress)

    val hello = root.kid("hello")
    assert(hello.count == 1)
    assert(hello.kid("@at").count == 1)
    val there = hello.kid("there")
    assert(there.count == 3)
    assert(there.kid("@it").count == 2)
    assert(there.kid("@was").count == 1)

    val json: JsValue = Json.toJson(root)
    println(Json.prettyPrint(json))

  }
}
