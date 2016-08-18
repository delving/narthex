package nxutil

import java.security.MessageDigest

import akka.actor.ActorContext
import dataset.DatasetActor.WorkFailure

object Utils {

  def sanitizedUsername(proposed: String) = proposed.replaceAll("[^\\w-]", "").toLowerCase

  // that's not how you do hashing/salting, folks...
  def hashPasswordUnsecure(password: String, salt: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val salted = password + salt
    val ba = digest.digest(salted.getBytes("UTF-8"))
    ba.map("%02x".format(_)).mkString
  }

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
