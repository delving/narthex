import harvest.PeriodicHarvest
import play.api._

object Global extends GlobalSettings {

  override def onStart(app: Application) {
    Logger.info("Narthex has started")
    PeriodicHarvest.startTicker()
  }

  override def onStop(app: Application) {
    Logger.info("Narthex shutdown...")
  }


}
