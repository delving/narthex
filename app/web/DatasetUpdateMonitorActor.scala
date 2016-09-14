package web

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.LoggingReceive
import org.slf4j.LoggerFactory
import web.DatasetUpdateMonitorActor.{AddWatcher, RemoveWatcher}

import scala.collection.immutable.HashSet

object DatasetUpdateMonitorActor {

  case object AddWatcher

  case object RemoveWatcher

  def props = Props[DatasetUpdateMonitorActor]
}

/**
  * Listens on /user/datasetUpdates and forwards to all UserActors.
  */
class DatasetUpdateMonitorActor extends Actor with ActorLogging {

  val logger = LoggerFactory.getLogger(this.getClass)

  protected[this] var watchers: HashSet[ActorRef] = HashSet.empty[ActorRef]

  override def receive = LoggingReceive {
    case AddWatcher => watchers = watchers + sender
    case RemoveWatcher => watchers = watchers - sender
    case _ => watchers.foreach( watcher =>  watcher ! _)
  }

}
