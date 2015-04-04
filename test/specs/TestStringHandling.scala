package specs

import java.io.File

import org.apache.commons.io.IOUtils
import org.joda.time.{DateTime, DateTimeZone, LocalDateTime}
import org.scalatest.{FlatSpec, Matchers}
import services.{FileHandling, StringHandling}
import services.StringHandling._
import services.Temporal._
import scala.collection.JavaConversions._

import scala.io.Source

class TestStringHandling extends FlatSpec with Matchers {

  "string handling" should "read csv" in {

    val csvDir = new File(getClass.getResource("/csv").getFile)
    val csvFiles = csvDir.listFiles.filter(_.getName.endsWith("csv"))

    csvFiles.foreach { csvFile =>
      val name = csvFile.getName
      val reader = FileHandling.createReader(csvFile)
      println(csvFile.toString)
      val xmlFile = new File(csvFile.getParent, s"$name.xml")
      val writer = FileHandling.createWriter(xmlFile)
      csvToXML(reader, writer)
      IOUtils.readLines(FileHandling.createReader(xmlFile)).foreach { line =>
        println(s"$name: $line")
      }
    }
  }
}
