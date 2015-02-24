package specs

import java.io.File

import mapping.CategoriesRepo
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}

class TestCategories extends PlaySpec with OneAppPerSuite with FakeTripleStore {

  "Category list should be read" in {
    val categoriesDir = new File(getClass.getResource("/categories").getFile)
    val categoriesRepo = new CategoriesRepo(categoriesDir)
    val list = categoriesRepo.categoryListOption.get
    list.size must be(23)
    list.map(println)
  }

}
