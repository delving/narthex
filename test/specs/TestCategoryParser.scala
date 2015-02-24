package specs

import java.io.{File, FileOutputStream}

import org.scalatest.{FlatSpec, Matchers}
import record.CategoryParser
import services.{FileHandling, ProgressReporter}

class TestCategoryParser extends FlatSpec with Matchers {

  val categoryMappingSource =
    <category-mappings>
      <category-mapping>
        <source>dimcon/Martena__test/input/dc:subject/Franeker</source>
        <categories>
          <schi/>
        </categories>
      </category-mapping>
      <category-mapping>
        <source>dimcon/Martena__test/input/dc:subject/Martinikerk</source>
        <categories>
          <tekn/>
        </categories>
      </category-mapping>
      <category-mapping>
        <source>dimcon/Martena__test/input/dc:subject/bolwerken</source>
        <categories>
          <grfk/>
          <kemk/>
        </categories>
      </category-mapping>
      <category-mapping>
        <source>dimcon/Martena__test/input/dc:subject/Westra-Bakker%2C%20M.</source>
        <categories>
          <bldh/>
        </categories>
      </category-mapping>
    </category-mappings>

  val categoryMappingList = CategoryDb.getList(categoryMappingSource)

  "category parser" should "count the occurrences of single, paired, and triple categories in records" in {
    val url = getClass.getResource("/categories/Martena.xml.gz")
    val file = new File(url.getFile)
    val (source, readProgress) = FileHandling.sourceFromFile(file)
    val recordRoot = "/delving-sip-source/input"
    val uniqueId = s"$recordRoot/@id"
    val pathPrefix = "dimcon/Martena__test"
    val categoryMappings = categoryMappingList.map(cm => (cm.source, cm)).toMap
    val categoryParser = new CategoryParser(pathPrefix, recordRoot, uniqueId, None, categoryMappings)
    case class Counter(var count: Int)
    val reply = categoryParser.parse(source, Set.empty[String], ProgressReporter())
    val countList = categoryParser.categoryCounts
    println(s"cat map $countList")
    reply should be(true)
    categoryParser.recordCount should be(4724)
    countList.size should be(13)

    val home = new File(System.getProperty("user.home"))
    val excel = new File(home, "categories.xlsx")
    val fos = new FileOutputStream(excel)
    CategoryParser.generateWorkbook(countList).write(fos)
    fos.close()
  }

  //  "category list" should "read in and turn to json" in {
  //    val url = getClass.getResource("/categories/Categories.xml")
  //    val file = new File(url.getFile)
  //    val catList = CategoryList.load(file)
  //    val json = Json.toJson(catList)
  //    println(Json.prettyPrint(json))
  //  }

}
