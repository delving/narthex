package services

import org.scalatest._
import play.api.libs.json.{JsValue, Json}
import scala.io.Source
import java.io.File
import scala.util.{Failure, Success}

class AnalyzerSpec extends FlatSpec with XRay {

  val source = Source.fromURL(getClass.getResource("/analyze-this.xml"))

  "The parse" should "reveal hello" in {

    def progress(elementCount: Long) {
      println(elementCount)
    }

    val directory = new FileAnalysisDirectory(new File("/tmp/AnalyzerSpec"))
    val hello = XRayNode(source, directory, progress) match {
      case Success(node) => node
      case Failure(ex) => throw new RuntimeException
    }

    assert(hello.count == 1)
    assert(hello.kid("@at").count == 1)
    val there = hello.kid("there")
    assert(there.count == 3)
    assert(there.kid("@it").count == 2)
    assert(there.kid("@was").count == 1)

    val json: JsValue = Json.toJson(hello)
    println(Json.prettyPrint(json))

  }
}
