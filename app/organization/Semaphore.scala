package organization

import java.util.concurrent.ConcurrentHashMap
import play.api.Logger

class Semaphore (val permits: Int) {

  val logger: Logger = Logger(this.getClass())

  private val semaphores = new ConcurrentHashMap[String, String]()

  def tryAcquire(spec: String): Boolean = {
    if ( semaphores.containsKey(spec) ) {
      logger.info(s"semaphore for $spec is still active or has not been released")
    }
    if (semaphores.size() < permits && !semaphores.containsKey(spec)) {
      semaphores.put(spec, "")
      true
    } else {
      false
    }
  }

  def isActive(spec: String): Boolean = semaphores.containsKey(spec)

  def release(spec: String) = {
    logger.info(s"releasing semaphore for $spec")
    semaphores.remove(spec)
  }

  def availablePermits(): Int = {
    permits - semaphores.size()
  }

  def inUse(): Int = semaphores.size()

  def activeSpecs() = {
    semaphores.keySet()
  }
}
