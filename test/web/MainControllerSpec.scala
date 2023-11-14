package web

import controllers.{AssetsFinder, MainController}
import init.NarthexConfig
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play._
import org.webjars.play.{RequireJS, WebJarAssets, WebJarsUtil}
import play.api.{Configuration, Environment}
import play.api.mvc.{ControllerComponents, Result, Results}
import play.api.test.{FakeRequest, Helpers}
import play.api.cache.{Cached, SyncCacheApi}
import play.api.test.Helpers._

import scala.concurrent.Future

class MainControllerSpec extends PlaySpec
  with Results
  with MockitoSugar {

  "the browser must invalidate the basic-auth credentials it supplied so the controller" should {
    "send 401 Unauthorized when logging out" in {

      implicit val webJarUtils: WebJarsUtil = mock[WebJarsUtil]
      implicit val assetsFinder: AssetsFinder = mock[AssetsFinder]

      val narthexConfig = new NarthexConfig(Configuration.load(Environment.simple()))
      val controller = new MainController(mock[Cached], narthexConfig)
      controller.setControllerComponents(Helpers.stubControllerComponents())

      val result: Future[Result] = controller.logout.apply(FakeRequest())

      status(result) mustBe 401
    }
  }

  "all deployments and nginx-configs out there assume that we run on /narthex so /" should {
    "redirect us to /narthex/" in {

      implicit val webJarUtils: WebJarsUtil = mock[WebJarsUtil]
      implicit val assetsFinder: AssetsFinder = mock[AssetsFinder]

      val narthexConfig = new NarthexConfig(Configuration.load(Environment.simple()))
      val controller = new MainController(mock[Cached], narthexConfig)
      controller.setControllerComponents(Helpers.stubControllerComponents())

      val result: Future[Result] = controller.root.apply(FakeRequest())

      status(result) mustBe 303
      header("Location", result) mustBe Some("/narthex/")
    }
  }

}
