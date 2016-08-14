package init

import com.kenshoo.play.metrics.{MetricsController, MetricsImpl}
import play.api._
import play.api.ApplicationLoader.Context
import play.api.cache.EhCacheComponents
import play.api.inject.{Injector, NewInstanceInjector, SimpleInjector}
import play.api.libs.concurrent.AkkaComponents
import play.api.libs.ws.ning.NingWSComponents
import play.api.mvc.{Handler, RequestHeader}
import play.api.routing.{SimpleRouter}
import router.{Routes => LegacyRoutes}

class MyApplicationLoader extends ApplicationLoader {
  def load(context: Context) = {
    Logger.info("initializing the application in play 2.4-style")

    new MyComponents(context).application
  }
}

class MyComponents(context: Context) extends BuiltInComponentsFromContext(context)
  with NingWSComponents
  with EhCacheComponents {

  lazy val router: play.api.routing.Router = ammendedRouter

  val legacyRoutes = LegacyRoutes.routes

  lazy val ammendedRouter = new SimpleRouter {
    def routes = ammendedRoutes(legacyRoutes)
  }

  //need to add any other components here that you want to reference via the global APIs  -
  //e.g. csrfConfig from CSRFComponents
  override lazy val injector: Injector = new SimpleInjector(
    NewInstanceInjector
  ) + router + crypto + httpConfiguration + defaultCacheApi + wsApi

  lazy val metrics = new MetricsImpl(applicationLifecycle, configuration)
  lazy val metricsController = new MetricsController(metrics)

  def ammendedRoutes(staticRoutesF: PartialFunction[RequestHeader, Handler]): PartialFunction[RequestHeader, Handler] = {
    staticRoutesF
  }
}

