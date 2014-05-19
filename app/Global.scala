import play.Logger
import services.Repository

object Global extends play.api.GlobalSettings {


  override def onStart(app: play.api.Application) {
    Repository.startBaseX()
    Logger.info("BaseX started")
  }

  override def onStop(app: play.api.Application) {
    Repository.stopBaseX()
    Logger.info("BaseX stopped")
  }
}

