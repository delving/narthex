import play.Logger
import services.Repo

object Global extends play.api.GlobalSettings {


  override def onStart(app: play.api.Application) {
    Repo.startBaseX()
    Logger.info("BaseX started")
  }

  override def onStop(app: play.api.Application) {
    Repo.stopBaseX()
    Logger.info("BaseX stopped")
  }
}

