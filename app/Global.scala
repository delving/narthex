import org.OrgContext._
import play.api._

object Global extends GlobalSettings {

  override def onStart(app: Application) {
    Logger.info("Narthex has started")
  }

  override def onStop(app: Application) {
    Logger.info("Shutting down tickers")
    harvestTicker.cancel()
    skosifierTicker.cancel()
    Logger.info("Narthex shutdown...")
//    periodicHarvest ! PoisonPill
//    skosifier ! PoisonPill
  }
}
