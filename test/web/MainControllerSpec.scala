package web

import org.scalatest.mock.MockitoSugar
import org.scalatestplus.play._
import org.webjars.play.RequireJS
import play.api.mvc.{Result, Results}
import play.api.test.FakeRequest
import play.api.cache.CacheApi
import play.api.test.Helpers._

import scala.concurrent.Future

class MainControllerSpec extends PlaySpec
  with Results
  with MockitoSugar {

  "the browser must invalidate the basic-auth credentials it supplied so the controller" should {
    "send 401 Unauthorized when logging out" in {

      val controller = new MainController(mock[CacheApi], "foo", "foo", "foo",
        mock[controllers.WebJarAssets], mock[RequireJS], "bar")

      val result: Future[Result] = controller.logout.apply(FakeRequest())

      status(result) mustBe 401
      contentAsString(result) mustBe ""
    }
  }

  "all deployments and nginx-configs out there assume that we run on /narthex so /" should {
    "redirect us to /narthex/" in {

      val controller = new MainController(mock[CacheApi], "foo", "foo", "foo",
        mock[controllers.WebJarAssets], mock[RequireJS], "bar")

      val result: Future[Result] = controller.root.apply(FakeRequest())

      status(result) mustBe 303
      header("Location", result) mustBe Some("/narthex/")
    }
  }
}
