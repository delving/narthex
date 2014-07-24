package services

import java.io.File

import org.apache.commons.io.FileUtils._
import org.scalatest.{FlatSpec, Matchers}
import services.Repo.TermMapping

class TestTermMatching extends FlatSpec with Matchers with TreeHandling {

  val userHome: String = "/tmp/narthex-user"
  System.setProperty("user.home", userHome)
  deleteQuietly(new File(userHome))

  "A NodeRepo" should "allow terminology mapping" in {
    val repo = Repo("test@narthex.delving.org")
    repo.create("password")
    val fileRepo = repo.fileRepo("pretend-file.xml.gz")

    def createNodeRepo(path: String) = {
      val nodeDir = path.split('/').toList.foldLeft(fileRepo.dir)((file, tag) => new File(file, Repo.tagToDirectory(tag)))
      nodeDir.mkdirs()
      fileRepo.nodeRepo(path).get
    }

    Repo.startBaseX()

    fileRepo.setMapping(TermMapping("a", "http://gumby.com/gumby-is-a-fink", "cusses"))
    fileRepo.setMapping(TermMapping("bb", "http://gumby.com/pokey", "cusses"))
    fileRepo.setMapping(TermMapping("a", "http://gumby.com/gumby", "cusses"))

    println(fileRepo.getMappings.toString())
    fileRepo.getMappings.toString() should be("List(TermMapping(a,http://gumby.com/gumby,cusses), TermMapping(bb,http://gumby.com/pokey,cusses))")
    fileRepo.getMapping("a") should be("http://gumby.com/gumby")
  }

}
