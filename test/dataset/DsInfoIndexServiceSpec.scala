package dataset

import scala.concurrent.{ExecutionContext, Future}

import init.NarthexConfig
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import organization.OrgContext
import services._

/** A externalIndex dataset depublishes/publishes through the index service and never reaches hub3. */
class DsInfoIndexServiceSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  implicit val ec: ExecutionContext = ExecutionContext.global

  private class Fake(result: Either[IndexServiceError, Long] = Right(1L)) extends IndexServiceApi {
    var calls = Vector.empty[String]
    def postJob(o: String, s: String, r: IndexJobRequest) = ???
    def jobStatus(o: String, s: String, j: String) = ???
    def depublish(o: String, s: String, by: String) = { calls :+= s"depublish $o/$s"; Future.successful(result) }
    def publish(o: String, s: String, by: String) = { calls :+= s"publish $o/$s"; Future.successful(result) }
  }

  private def dsInfo(api: Option[IndexServiceApi]): (DsInfo, OrgContext, DatasetsDb) = {
    val config = mock[NarthexConfig]
    when(config.orgId).thenReturn("org")
    when(config.nxUriPrefix).thenReturn("http://x/")
    val db = mock[DatasetsDb]
    when(db.getProp("ds", "externalIndex")).thenReturn(Some("true"))
    val orgCtx = mock[OrgContext]
    when(orgCtx.appConfig).thenReturn(config)
    when(orgCtx.datasetsDb).thenReturn(db)
    when(orgCtx.indexServiceApi).thenReturn(api)
    when(orgCtx.datasetsDir).thenReturn(new java.io.File(System.getProperty("java.io.tmpdir"), "narthex-dsinfo-index-service"))
    (new DsInfo("ds", "http://x/", "tok", "http://nave", orgCtx, mockBulkApi = false), orgCtx, db)
  }

  "dropDataset (delete)" should "depublish and then delete the local row, without hub3" in {
    val api = new Fake
    val (info, orgCtx, db) = dsInfo(Some(api))
    info.dropDataset
    api.calls shouldBe Vector("depublish org/ds")
    verify(db).deleteDataset("ds")
    verify(orgCtx, never).wsApi
  }

  it should "stop before deleting when depublish fails" in {
    val api = new Fake(Left(IndexServiceUnreachable("down")))
    val (info, orgCtx, db) = dsInfo(Some(api))
    val ex = intercept[RuntimeException](info.dropDataset)
    ex.getMessage should include("depublish failed")
    verify(db, never).deleteDataset("ds")
    verify(orgCtx, never).wsApi
  }

  "dropDatasetIndex (delete index, disable, depublication)" should "depublish without hub3" in {
    val api = new Fake
    val (info, orgCtx, _) = dsInfo(Some(api))
    info.dropDatasetIndex
    api.calls shouldBe Vector("depublish org/ds")
    verify(orgCtx, never).wsApi
  }

  it should "throw and not fall back to hub3 when the index service is not configured" in {
    val (info, orgCtx, _) = dsInfo(None)
    intercept[RuntimeException](info.dropDatasetIndex).getMessage should include("not configured")
    verify(orgCtx, never).wsApi
  }

  "setIndexPublished(true) (enable dataset)" should "publish" in {
    val api = new Fake
    val (info, orgCtx, _) = dsInfo(Some(api))
    info.setIndexPublished(true)
    api.calls shouldBe Vector("publish org/ds")
    verify(orgCtx, never).wsApi
  }

  private val failed = Left(IndexServiceUnreachable("down"))

  "DatasetContext.disableDataSet" should "depublish first and not mark DISABLED when depublish fails" in {
    val (info, orgCtx, db) = dsInfo(Some(new Fake(failed)))
    intercept[RuntimeException](new DatasetContext(orgCtx, info).disableDataSet)
    verify(db, never).setProps(org.mockito.ArgumentMatchers.eq("ds"), org.mockito.ArgumentMatchers.any[(String, String)]())
    verify(orgCtx, never).wsApi
  }

  it should "mark DISABLED after a successful depublish" in {
    val api = new Fake
    val (info, orgCtx, db) = dsInfo(Some(api))
    new DatasetContext(orgCtx, info).disableDataSet
    api.calls shouldBe Vector("depublish org/ds")
    verify(db).setProps(org.mockito.ArgumentMatchers.eq("ds"), org.mockito.ArgumentMatchers.any[(String, String)]())
    verify(orgCtx, never).wsApi
  }

  "enableDataset" should "publish, then clear DISABLED" in {
    val api = new Fake
    val (info, orgCtx, db) = dsInfo(Some(api))
    info.enableDataset()
    api.calls shouldBe Vector("publish org/ds")
    verify(db).removeProp("ds", "stateDisabled")
    verify(orgCtx, never).wsApi
  }

  it should "keep the dataset DISABLED when publish fails" in {
    val (info, orgCtx, db) = dsInfo(Some(new Fake(failed)))
    intercept[RuntimeException](info.enableDataset())
    verify(db, never).removeProp("ds", "stateDisabled")
    verify(orgCtx, never).wsApi
  }

  // The actor wraps these in Try(...) for the noRecordsMatch paths and in its command Try for delete;
  // wiring the FSM actor is impractical, so the narrowest callees are tested: dropIndex depublishes
  // (both noRecordsMatch paths) and throws on failure, dropDataset throws before the caller deletes rootDir.
  "DatasetContext.dropIndex (noRecordsMatch paths)" should "depublish, and surface a failure to the actor's Try" in {
    val api = new Fake
    val (info, orgCtx, _) = dsInfo(Some(api))
    new DatasetContext(orgCtx, info).dropIndex
    api.calls shouldBe Vector("depublish org/ds")
    val (info2, orgCtx2, _) = dsInfo(Some(new Fake(failed)))
    scala.util.Try(new DatasetContext(orgCtx2, info2).dropIndex).isFailure shouldBe true
    verify(orgCtx, never).wsApi
    verify(orgCtx2, never).wsApi
  }
}
