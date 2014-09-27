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

    def gatherPaths(node: ReadTreeNode): List[String] = {
      if (node.lengths.length > 0) {
        node.path :: node.kids.flatMap(gatherPaths).toList
      }
      else {
        node.kids.flatMap(gatherPaths).toList
      }
    }

    val paths = gatherPaths(tree)

    paths.head should be("/car:carareWrap/car:carare/@id")
    paths.last should be("/car:carareWrap/car:carare/car:digitalResource/car:format")
    paths.size should be(31)
  }
}
