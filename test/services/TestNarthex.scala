package services

import java.io.File
import org.apache.commons.io.FileUtils._
import play.api.test._
import play.api.libs.json.Json

class TestNarthex extends PlaySpecification with XRay {

//  override def is = args(sequential = true) ^ super.is

  //  val random = new RandomSample(10, new Random(939))
  //
  //  "Random sampler" should "keep the right values" in {
  //
  //    val strings = for (i <- 1 to 50) yield s"string$i"
  //    strings.foreach(random.record)
  //
  //    val values = random.values
  //    assert(values.size == 10)
  //
  //    println(random.values)
  //  }
  //
  val userHome: String = "/tmp/narthex-user"
  System.setProperty("user.home", userHome)
  deleteQuietly(new File(userHome))

  val repo = Repo("test@narthex.delving.org")
  repo.create("password")

  val fileName = "Martena.xml.gz"
  copyFileToDirectory(new File(getClass.getResource(s"/$fileName").getFile), repo.uploaded)

  object FakeApp extends FakeApplication()

  running(FakeApp) {

    "source file should be processed" in {

      val filesToAnalyze = repo.scanForWork()

      "files to analyze" should {

        "be of length one" in {
          filesToAnalyze must have size 1
        }

        "contain our file" in {
          filesToAnalyze.head.getName must equalTo(fileName)
        }
      }

      val fileRepo = repo.fileRepo(fileName)

      def waitForStatus() = {
        while (!fileRepo.status.exists()) {
          println("waiting for status..")
          Thread.sleep(10)
        }
      }

      "the file repository" should {

        "exist" in {
          fileRepo.dir.exists() must equalTo(true)
        }

        "have status complete" in {
          waitForStatus()
          val status = Json.parse(readFileToString(fileRepo.status))
          (status \ "complete").as[Boolean] must equalTo(true)
        }

        var baseXDir = new File(Repo.root, "basex")
        var databaseDir = new File(new File(baseXDir, "data"), repo.personalRootName)

        "create a basex directory when told to store records" in {
//          baseXDir.exists() must equalTo(false)  //it's actually already there, why?
          Repo.startBaseX()
          baseXDir.exists() must equalTo(true)
          databaseDir.exists() must equalTo(false)
          fileRepo.storeRecords("/delving-sip-source/input", "/delving-sip-source/input/@id")
          Repo.stopBaseX() // cannot put this after the following line.. why?
          databaseDir.exists() must equalTo(true)
        }

      }

    }

    Thread.sleep(4000) // must wait until the actors have had the chance to do their work
    println("sleep is done")
  }
  //
  //  "The parse" should "reveal hello" in {
  //
  //    def progress(elementCount: Long) {
  //      println(elementCount)
  //    }
  //
  //    val directory = new FileRepo(new File("/tmp/AnalyzerSpec"))
  //    val hello = XRayNode(source, directory, progress) match {
  //      case Success(node) => node
  //      case Failure(ex) => throw new RuntimeException
  //    }
  //
  //    assert(hello.count == 1)
  //    assert(hello.kid("@at").count == 1)
  //    val there = hello.kid("there")
  //    assert(there.count == 3)
  //    assert(there.kid("@it").count == 2)
  //    assert(there.kid("@was").count == 1)
  //
  //    val json: JsValue = Json.toJson(hello)
  //    println(Json.prettyPrint(json))
  //
  //  }


}
