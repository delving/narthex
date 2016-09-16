package init

import java.io.File
import java.util

import akka.actor.{ActorRef, Props}
import com.kenshoo.play.metrics.{MetricsController, MetricsFilterImpl, MetricsImpl}
import controllers.WebJarAssets
import harvest.PeriodicHarvest
import harvest.PeriodicHarvest.ScanForHarvests
import mapping.PeriodicSkosifyCheck
import org._
import play.api._
import play.api.ApplicationLoader.Context
import play.api.cache.EhCacheComponents
import triplestore.Fuseki
import web._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.mailer._
import play.api.libs.ws.ahc.AhcWSComponents
import services.{MailService, MailServiceImpl}

import scala.concurrent.duration._
import collection.JavaConversions._
import scala.concurrent.Future
import router.Routes // this is the class that Play generates based on the contents of the Routes file

class MyApplicationLoader extends ApplicationLoader {

  val topActorConfigProp = "topActor.initialPassword"

  def load(context: Context) = {
    Logger.info("Narthex starting up...")

    val initialPassword: String = context.initialConfiguration.getString(topActorConfigProp).
      getOrElse(throw new RuntimeException(s"${topActorConfigProp} not set"))
    val homeDir: String = context.initialConfiguration.getString("narthexHome").
      getOrElse(System.getProperty("user.home") + "/NarthexFiles")

    val narthexDataDir: File = new File(homeDir)
    if (! narthexDataDir.canWrite ) {
      throw new RuntimeException(s"Configured $narthexDataDir is not writeable")
    }
    val components = new MyComponents(context, narthexDataDir)

    val userRepository: UserRepository = components.userRepository
    userRepository.hasAdmin.
      filter( hasAdmin => !hasAdmin).
      map { hasAdmin =>
        userRepository.insertAdmin(initialPassword)
        Logger.info(s"Inserted initial admin user, details")
      }
    components.application
  }
}

object MyComponents {
  val updateMonitorActorName = "datasetUpdates"
  val updateMonitorActorPath = s"user/$updateMonitorActorName"
}

class MyComponents(context: Context, narthexDataDir: File) extends BuiltInComponentsFromContext(context)
  with AhcWSComponents
  with EhCacheComponents
  with MailerComponents {

  LoggerConfigurator(context.environment.classLoader).foreach { _.configure(context.environment) }

  val appConfig = initAppConfig(narthexDataDir)


  lazy val orgContext = new OrgContext(appConfig, defaultCacheApi, wsClient,
    mailService, authenticationService, userRepository, orgActorRef)

  lazy val router = new Routes(httpErrorHandler, mainController, webSocketController, appController,
    sipAppController, apiController, webJarAssets, assets, metricsController, infoController)

  lazy val orgActorRef: ActorRef = actorSystem.actorOf(Props(new OrgActor(orgContext)), appConfig.orgId)

  lazy val metrics = new MetricsImpl(applicationLifecycle, configuration)

  lazy val metricsFilter = new MetricsFilterImpl(metrics)

  override lazy val httpFilters = List(metricsFilter)

  lazy val metricsController = new MetricsController(metrics)
  lazy val sipAppController: SipAppController = new SipAppController(defaultCacheApi, orgContext)
  lazy val mainController = new MainController(userRepository, authenticationService, defaultCacheApi,
    appConfig.apiAccessKeys, appConfig.narthexDomain, appConfig.naveDomain, appConfig.orgId
  )
  lazy val appController = new AppController(defaultCacheApi, orgContext) (tripleStore, actorSystem, materializer)
  lazy val apiController = new APIController(appConfig.apiAccessKeys, orgContext)
  lazy val infoController = new InfoController

  lazy val datasetUpdateMonitorActor = actorSystem.actorOf(DatasetUpdateMonitorActor.props, MyComponents.updateMonitorActorName)
  lazy val userParentActor = actorSystem.actorOf(Props(classOf[UserParentActor], datasetUpdateMonitorActor))
  lazy val webSocketController =
    new WebSocketController(defaultCacheApi, datasetUpdateMonitorActor, userParentActor)(actorSystem, materializer, defaultContext)

  lazy val assets = new controllers.Assets(httpErrorHandler)
  lazy val webJarAssets = new WebJarAssets(httpErrorHandler, configuration, environment)

  val tripleStoreUrl = configString("triple-store")
  val tripleStoreLog = configFlag("triple-store-log")
  implicit val tripleStore = new Fuseki(tripleStoreUrl, tripleStoreLog, wsApi)

  lazy val authenticationService = AuthenticationMode.
    fromConfigString(configuration.getString(AuthenticationMode.PROPERTY_NAME)) match {
      case AuthenticationMode.MOCK => new MockAuthenticationService
      case AuthenticationMode.TS => new TsBasedAuthenticationService
  }


  lazy val userRepository: UserRepository = {
    UserRepository.Mode.fromConfigString(configuration.getString(UserRepository.Mode.PROPERTY_NAME)) match {
      case UserRepository.Mode.MOCK => new MockUserRepository(appConfig.nxUriPrefix)
      case UserRepository.Mode.TS => new ActorStore(authenticationService, appConfig.nxUriPrefix)
    }
  }

  val periodicHarvest = actorSystem.actorOf(PeriodicHarvest.props(orgContext), "PeriodicHarvest")
  val harvestTicker = actorSystem.scheduler.schedule(1.minute, 1.minute, periodicHarvest, ScanForHarvests)
  val periodicSkosifyCheck = actorSystem.actorOf(PeriodicSkosifyCheck.props(orgContext), "PeriodicSkosifyCheck")

  val check = Future(orgContext.sipFactory.prefixRepos.map(repo => repo.compareWithSchemasDelvingEu()))
  check.onFailure { case e: Exception => throw new RuntimeException(e) }

  val xsdValidation = configFlag("xsd-validation")
  System.setProperty("XSD_VALIDATION", xsdValidation.toString)

  lazy val mailService: MailService = new MailServiceImpl(mailerClient, userRepository, true)

  applicationLifecycle.addStopHook { () =>
    Future.successful({
      Logger.info("Narthex shutting down, cleaning up active threads...")
      harvestTicker.cancel()
    })
  }

  private def initAppConfig(narthexDataDir: File): AppConfig = {
    val harvestTimeout = configuration.getInt("harvest.timeout").getOrElse(3 * 60 * 1000)
    val rdfBaseUrl = configStringNoSlash("rdfBaseUrl")
    val narthexDomain = configStringNoSlash("domains.narthex")
    val naveDomain = configStringNoSlash("domains.nave")
    val apiAccessKeys = secretList("api.accessKeys").toList

    AppConfig(
      harvestTimeout, configFlag("useBulkApi"), rdfBaseUrl,
      configStringNoSlash("naveApiUrl"), configStringNoSlash("naveAuthToken"),
      narthexDataDir, configString("orgId"), narthexDomain, naveDomain,
      apiAccessKeys)
  }

  private def configFlag(name: String): Boolean = configuration.getBoolean(name).getOrElse(false)

  private def configString(name: String) = configuration.getString(name).
    getOrElse(throw new RuntimeException(s"Missing config string: $name"))

  private def configStringNoSlash(name: String) = configString(name).replaceAll("\\/$", "")

  private def configInt(name: String) = configuration.getInt(name).
    getOrElse(throw new RuntimeException(s"Missing config int: $name"))

  private def secretList(name: String): util.List[String] = configuration.getStringList(name).getOrElse(List("secret"))
}

/**
  * Contains all application-specific configuration-values obtained from the play environment and that are used
  * by various components
  */
case class AppConfig(harvestTimeOut: Long, useBulkApi: Boolean, rdfBaseUrl: String,
                     naveApiUrl: String, naveApiAuthToken: String,
                     narthexDataDir: File, orgId: String,
                     narthexDomain: String, naveDomain: String, apiAccessKeys: List[String]) {

  def nxUriPrefix: String = s"$rdfBaseUrl/resource"
}
