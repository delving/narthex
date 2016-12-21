package nxutil

import akka.actor.ActorContext
import dataset.DatasetActor.WorkFailure

object Utils {

  def actorWork(actorContext: ActorContext)(block: => Unit) = {
    try {
      block
    }
    catch {
      case e: Throwable =>
        actorContext.parent ! WorkFailure(e.getMessage, Some(e))
    }
  }
}
