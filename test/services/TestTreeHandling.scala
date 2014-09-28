package services

import org.apache.commons.io.IOUtils
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.Json

class TestTreeHandling extends FlatSpec with Matchers with TreeHandling {


  "tree handling" should "read in a tree" in {

    val url = getClass.getResource("/tree/index.json")
    val string = IOUtils.toString(url.openStream())
    val json= Json.parse(string)
    val tree = json.as[ReadTreeNode]
    val baseUrl = "http://thingy.com/api/bla"
    val paths = gatherPaths(tree, baseUrl)

    paths.head should be(PathNode("http://thingy.com/api/bla/histogram/car_carareWrap/car_carare/_id", 63930))
    paths.last should be(PathNode("http://thingy.com/api/bla/histogram/car_carareWrap/car_carare/car_digitalResource/car_format", 63930))
    paths.size should be(31)
  }
}
