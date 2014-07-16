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

    val nodeRepo = createNodeRepo("list/record/term")

    Repo.startBaseX()

    nodeRepo.addMapping(TermMapping("a", "http://gumby.com/gumby-is-a-fink"))
    nodeRepo.addMapping(TermMapping("b b", "http://gumby.com/pokey"))
    nodeRepo.addMapping(TermMapping("a", "http://gumby.com/gumby"))

    val prefix = "/test_narthex_delving_org/pretend-file/list/record/term"

    fileRepo.getMappings.toString() should be (s"List(TermMapping($prefix/a,http://gumby.com/gumby), TermMapping($prefix/b+b,http://gumby.com/pokey))")
    fileRepo.getMapping(s"$prefix/a") should be ("http://gumby.com/gumby")
  }

}
