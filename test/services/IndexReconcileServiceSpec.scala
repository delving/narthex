package services

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

import init.NarthexConfig
import org.mockito.Mockito.{verifyNoInteractions, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import organization.OrgContext
import play.api.libs.ws.WSClient

class IndexReconcileServiceSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  implicit val ec: ExecutionContext = ExecutionContext.global

  "IndexReconcileService.reconcile" should "refuse an externalIndex dataset without calling hub3 or the registry" in {
    val config = mock[NarthexConfig]
    when(config.orgId).thenReturn("testorg")
    val db = mock[DatasetsDb]
    when(db.getProp("ds", "externalIndex")).thenReturn(Some("true"))
    val ws = mock[WSClient]
    val registry = mock[RecordRegistry]
    val orgContext = mock[OrgContext]
    when(orgContext.appConfig).thenReturn(config)
    when(orgContext.datasetsDb).thenReturn(db)
    when(orgContext.wsApi).thenReturn(ws)
    when(orgContext.recordRegistry).thenReturn(registry)

    val e = intercept[ExternalIndexException](Await.result(new IndexReconcileService(orgContext).reconcile("ds"), 5.seconds))
    e.getMessage should (include("index service") and include("resync route"))
    verifyNoInteractions(ws)
    verifyNoInteractions(registry)
  }
}
