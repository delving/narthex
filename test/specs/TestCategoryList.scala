package specs

import java.io.File

import mapping.CategoryList
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.Json

class TestCategoryList extends FlatSpec with Matchers {

  "category list" should "read in and turn to json" in {
    val url = getClass.getResource("/categories/categories.xml")
    val file = new File(url.getFile)
    val catList = CategoryList.load(file)
    val json = Json.toJson(catList)
    println(Json.prettyPrint(json))
  }
}
