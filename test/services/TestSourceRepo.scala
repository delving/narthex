package services

import java.io.File

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FileUtils._
import org.scalatest.{FlatSpec, Matchers}

class TestSourceRepo extends FlatSpec with Matchers with RecordHandling {

  val recordRoot = "/envelope/list/thing"
  val uniqueId = s"$recordRoot/@which"
  val dir = new File("/tmp/test-source-repo")
  deleteQuietly(dir)
  val incoming = new File("/tmp/test-source-repo-incoming")
  deleteQuietly(incoming)

  def resourceFile(letter: String): File = {
    val name = s"source-$letter.xml"
    val url = getClass.getResource(s"/source/$name")
    new File(url.getFile)
  }

  List("a", "b", "c").map(resourceFile).foreach(f => FileUtils.copyFile(f, new File(incoming, f.getName)))

  def incomingFile(letter: String): File = {
    val name = s"source-$letter.xml"
    new File(incoming, name)
  }

  val sourceRepo = new SourceRepo(dir, recordRoot, uniqueId)

  "A Source Repository" should "accept files" in {

    sourceRepo.xmlFiles.length should be(0)
    sourceRepo.nextFileNumber should be(0)

    sourceRepo.acceptFile(incomingFile("a"))
    sourceRepo.nextFileNumber should be(1)


    sourceRepo.acceptFile(incomingFile("b"))
    sourceRepo.nextFileNumber should be(2)


    sourceRepo.acceptFile(incomingFile("c"))
    sourceRepo.nextFileNumber should be(3)


  }

}
