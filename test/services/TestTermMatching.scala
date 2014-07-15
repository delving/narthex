package services

import java.io.File

import org.apache.commons.io.FileUtils._
import org.scalatest.{FlatSpec, Matchers}

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

    nodeRepo.initMappings()
    nodeRepo.addMapping(TermMapping("from", "to"))


  }

}
