package specs

import java.io.File

import dataset.SourceRepo.{IdFilter, SHA256_FILTER, VERBATIM_FILTER}
import dataset.{DsInfo, ProcessedRepo}
import org.ActorStore
import org.ActorStore.NXActor
import org.OrgContext._
import org.joda.time.DateTime
import org.scalatestplus.play._
import play.api.test.Helpers._
import record.PocketParser.Pocket
import services.FileHandling._
import services.{FileHandling, ProgressReporter}
import triplestore.Sparql._

class TestOrphanDelete extends PlaySpec with OneAppPerSuite with PrepareEDM with FakeTripleStore {

  lazy val actorPrefix = s"$NX_URI_PREFIX/actor"
  val actorStore = new ActorStore

  "Orphans should be deleted" in {
    cleanStart()
    val admin = await(actorStore.authenticate("gumby", "secret gumby"))
    admin must be(Some(NXActor("gumby", None)))
    // prepare for reading and mapping
    val sipRepo = createSipRepoFromDir(0)
    val latestSip = sipRepo.latestSipOpt.get
    val info = await(DsInfo.createDsInfo(admin.get, "ton-smits-huis", DsInfo.CharacterMapped, "edm"))

    def process(processedDir: File, idFilter: IdFilter, timeStamp: DateTime) = {
      val processedRepo = new ProcessedRepo(FileHandling.clearDir(processedDir), info)
      var processedFile = processedRepo.createOutput.xmlFile
      val processedWriter = createWriter(processedFile)
      // fill processed repo by mapping records
      val source = latestSip.copySourceToTempFile
      source.isDefined must be(true)
      val sourceRepo = createSourceRepo
      sourceRepo.acceptFile(source.get, ProgressReporter())
      var mappedPockets = List.empty[Pocket]
      latestSip.createSipMapper.map { sipMapper =>
        def pocketCatcher(pocket: Pocket): Unit = {
          var mappedPocket = sipMapper.executeMapping(pocket)
          mappedPocket.map(_.writeTo(processedWriter))
          mappedPockets = mappedPocket.get :: mappedPockets
        }
        sourceRepo.parsePockets(pocketCatcher, idFilter, ProgressReporter())
      }
      mappedPockets
      mappedPockets.size must be(3)
      processedWriter.close()

      val graphReaderA = processedRepo.createGraphReader(None, timeStamp, ProgressReporter())
      while (graphReaderA.isActive) {
        graphReaderA.readChunkOpt.foreach(chunk => await(ts.up.sparqlUpdate(chunk.sparqlUpdateQ)))
      }
    }

    val stampA = new DateTime()
    process(new File("/tmp/test-orphan-a"), VERBATIM_FILTER, stampA)
    countGraphs must be(5)

    val stampB = stampA.plusDays(1)
    process(new File("/tmp/test-orphan-b"), SHA256_FILTER, stampB)
    countGraphs must be(8)

    // now delete old ones
    await(ts.up.sparqlUpdate(deleteOlderGraphs(stampB, info.uri)))
    countGraphs must be(5)
  }

  "Delete a dataset" in {
    DsInfo.withDsInfo("ton-smits-huis")(dsInfo => await(dsInfo.dropDataset))
    await(DsInfo.listDsInfo) must be(List())
  }

}
