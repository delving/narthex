package specs

import java.io.File

import mapping.VocabInfo
import org.ActorStore
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}
import play.api.test.Helpers._

class TestCategories extends PlaySpec with OneAppPerSuite with FakeTripleStore {

  "Category list should be read" in {
    cleanStart()
    val actorStore = new ActorStore(ts)
    val admin = await(actorStore.authenticate("gumby", "secret gumby")).get
    val categoriesVocabInfo = await(VocabInfo.createVocabInfo(admin, VocabInfo.CATEGORIES_SPEC, ts))
    val categoriesFile = new File(getClass.getResource("/categories/Categories.xml").getFile)
    await(ts.up.dataPutXMLFile(categoriesVocabInfo.dataUri, categoriesFile))
    val v = categoriesVocabInfo.vocabulary
    val cl = v.concepts
    println(s"Concepts: ${cl.size}")
    cl.map { c =>
      println("boo")
      println(c)
    }
    println("done")
  }

}
