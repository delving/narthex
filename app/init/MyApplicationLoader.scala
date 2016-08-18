package init

import java.io.File
import java.util

import com.kenshoo.play.metrics.{MetricsController, MetricsImpl}
import controllers.WebJarAssets
import harvest.PeriodicHarvest
import harvest.PeriodicHarvest.ScanForHarvests
import mapping.PeriodicSkosifyCheck
import org._
import play.api._
import play.api.ApplicationLoader.Context
import play.api.cache.EhCacheComponents
import play.api.libs.ws.ning.NingWSComponents
import play.libs.Akka._
import triplestore.Fuseki
import web.{APIController, AppController, MainController, SipAppController}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.mailer._
import services.{MailService, MailServiceImpl}

import scala.concurrent.duration._
import collection.JavaConversions._
import scala.concurrent.Future
import router.Routes // this is the class that Play generates based on the contents of the Routes file

class MyApplicationLoader extends ApplicationLoader {

  val topActorConfigProp = "topActor.initialPassword"

  def load(context: Context) = {
    Logger.info("initializing the application in play 2.4-style")

    val components = new MyComponents(context)
    val initialPassword: String = context.initialConfiguration.getString(topActorConfigProp).getOrElse(throw new RuntimeException(s"${topActorConfigProp} not set"))

    val userRepository: UserRepository = components.userRepository
    userRepository.hasAdmin.
      filter( hasAdmin => !hasAdmin).
      map { hasAdmin =>
        val topActorConfigProp = "topActor.initialPassword"
        userRepository.insertAdmin(initialPassword)
        Logger.info(s"Inserted initial admin user, details")
      }


    components.application
  }
}

class MyComponents(context: Context) extends BuiltInComponentsFromContext(context)
  with NingWSComponents
  with EhCacheComponents
  with MailerComponents {


  lazy val orgContext = new OrgContext(defaultCacheApi, wsClient, harvestTimeout, useBUlkApi,
    rdfBaseUrl, nxUriPrefix, naveApiUrl,logBulkApi, naveBulkApiAuthToken, narthexDatadir, mailService,
    authenticationService, userRepository, userHome, orgId)

  lazy val router = new Routes(httpErrorHandler, mainController, appController,
    sipAppController, apiController, webJarAssets, assets, metricsController)


  private lazy val metrics = new MetricsImpl(applicationLifecycle, configuration)

  private lazy val metricsController = new MetricsController(metrics)
  private lazy val sipAppController: SipAppController = new SipAppController(defaultCacheApi, orgContext)
  private lazy val mainController = new MainController(userRepository, authenticationService, defaultCacheApi,
    apiAccessKeys,
    narthexDomain, naveDomain, orgId
  )
  private lazy val appController = new AppController(defaultCacheApi, orgContext)(tripleStore)
  private lazy val apiController = new APIController(apiAccessKeys, orgContext)

  lazy val assets = new controllers.Assets(httpErrorHandler)
  lazy val webJarAssets = new WebJarAssets(httpErrorHandler, configuration, environment)

  val tripleStoreUrl = configString("triple-store")
  val tripleStoreLog = configFlag("triple-store-log")
  implicit val tripleStore = new Fuseki(tripleStoreUrl, tripleStoreLog, wsApi)

  lazy val authenticationService = AuthenticationMode.fromConfigString(configuration.getString(AuthenticationMode.PROPERTY_NAME)) match {
    case AuthenticationMode.MOCK => new MockAuthenticationService
    case AuthenticationMode.TS => new TsBasedAuthenticationService
  }


  lazy val userRepository: UserRepository = {
    UserRepository.Mode.fromConfigString(configuration.getString(UserRepository.Mode.PROPERTY_NAME)) match {
      case UserRepository.Mode.MOCK => new MockUserRepository(nxUriPrefix)
      case UserRepository.Mode.TS => new ActorStore(authenticationService, nxUriPrefix, orgContext)
    }
  }


  val orgId = configString("orgId")
  val narthexDomain = configStringNoSlash("domains.narthex")
  val naveDomain = configStringNoSlash("domains.nave")
  val naveApiUrl = configStringNoSlash("naveApiUrl")

  val naveBulkApiAuthToken = configStringNoSlash("naveAuthToken")
  val useBUlkApi = configFlag("useBulkApi")
  val logBulkApi = configFlag("logBulkApi")

  val rdfBaseUrl = configStringNoSlash("rdfBaseUrl")

  val nxUriPrefix = s"$rdfBaseUrl/resource"

  val userHome = System.getProperty("user.home")
  val narthexDatadir = new File(userHome, "NarthexFiles")

  val apiAccessKeys = secretList("api.accessKeys").toList

  val harvestTimeout = configuration.getInt("harvest.timeout").getOrElse(3 * 60 * 1000)

  val periodicHarvest = actorSystem.actorOf(PeriodicHarvest.props(orgContext), "PeriodicHarvest")
  val harvestTicker = actorSystem.scheduler.schedule(1.minute, 1.minute, periodicHarvest, ScanForHarvests)
  val periodicSkosifyCheck = actorSystem.actorOf(PeriodicSkosifyCheck.props(orgContext), "PeriodicSkosifyCheck")

  val check = Future(orgContext.sipFactory.prefixRepos.map(repo => repo.compareWithSchemasDelvingEu()))
  check.onFailure { case e: Exception => Logger.error("Failed to check schemas", e) }

  val xsdValidation = configFlag("xsd-validation")
  System.setProperty("XSD_VALIDATION", xsdValidation.toString)

  lazy val mailService: MailService = new MailServiceImpl(mailerClient, userRepository, true)

  applicationLifecycle.addStopHook { () =>
    Future.successful({
      Logger.info("Narthex shutting down, cleaning up active threads...")
      harvestTicker.cancel()
    })
  }

  private def configFlag(name: String): Boolean = configuration.getBoolean(name).getOrElse(false)

  private def configString(name: String) = configuration.getString(name).getOrElse(throw new RuntimeException(s"Missing config string: $name"))

  private def configStringNoSlash(name: String) = configString(name).replaceAll("\\/$", "")

  private def configInt(name: String) = configuration.getInt(name).getOrElse(throw new RuntimeException(s"Missing config int: $name"))

  private def secretList(name: String): util.List[String] = configuration.getStringList(name).getOrElse(List("secret"))


}