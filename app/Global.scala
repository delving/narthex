import init.AuthenticationMode
import org.OrgContext._
import org.UserRepository
import play.api._
import play.api.libs.concurrent.Execution.Implicits


object Global extends GlobalSettings {

  val topActorConfigProp = "topActor.initialPassword"

  override def beforeStart(app: Application) {
    val authMode = AuthenticationMode.fromConfigString(app.configuration.getString(AuthenticationMode.PROPERTY_NAME))
    val backingRepo = UserRepository.Mode.fromConfigString(app.configuration.getString(UserRepository.Mode.PROPERTY_NAME))

    Logger.info(s"Narthex initializing for ${orgContext.orgId}, authMode: $authMode, backingRepo: $backingRepo")
  }


  override def onStart(app: Application) {
    import play.api.libs.concurrent.Execution.Implicits._

    val initialPassword: String = config.getString(topActorConfigProp).
      getOrElse(throw new RuntimeException(s"${topActorConfigProp} not set"))

    orgContext.us.hasAdmin.
      filter( hasAdmin => !hasAdmin).
      map { hasAdmin =>
        val topActorConfigProp = "topActor.initialPassword"
        orgContext.us.insertAdmin(initialPassword)
        Logger.info(s"Inserted initial admin user, details")
    }
  }

  override def onStop(app: Application) {
    Logger.info("Shutting down tickers")
    harvestTicker.cancel()
//    skosifyTicker.cancel()
    Logger.info("Narthex shutdown...")
  }
}
