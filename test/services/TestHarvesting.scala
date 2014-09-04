package services

import java.io.File

import actors.{HarvestAdLib, Harvester}
import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import org.apache.commons.io.FileUtils._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

class TestHarvesting(_system: ActorSystem) extends TestKit(_system)
with ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with Harvesting with ScalaFutures {

  def this() = this(ActorSystem("TestHarvesting"))

  override def afterAll() = TestKit.shutdownActorSystem(system)

  "The AdLib Harvester" should "fetch pages" in {

    val testUrl = "http://umu.adlibhosting.com/api/wwwopac.ashx"
    val testDatabase = "collect"
    val userHome = "/tmp/narthex-user"
    deleteQuietly(new File(userHome))

    val repo = new Repo(userHome, "umu")
    repo.create("passwhat?")
    val fileRepo = repo.fileRepo("umu-test__adlib")
    val harvester = system.actorOf(Props(new Harvester(fileRepo)))

    harvester ! HarvestAdLib(testUrl, testDatabase)

    awaitCond((fileRepo.datasetDb.getDatasetInfo \ "status" \ "state").text == RepoUtil.State.SAVED, 10.minutes, 10.seconds)
  }

//  "The PMH Harvester" should "fetch pages" in {
//
//    val testUrl = "http://62.221.199.184:7829/oai"
//    val testSet = ""
//    val testPrefix = "oai_dc"
//
//    var count = 0
//
//    whenReady(fetchPMHPage(testUrl, testSet, testPrefix, None), timeout(30 seconds)) {
//      p =>
//        println(s"page $count: ${p.records.length}")
//        println(s"Resumption: ${p.resumptionToken}")
//
//        p.resumptionToken match {
//          case Some(token) =>
//            println(s"second: ${p.records}")
//          case None =>
//            println("FINISHED RIGHT AWAY")
//        }
//    }
//  }
}
