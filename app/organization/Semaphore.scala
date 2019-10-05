package organization

import java.util.concurrent.ConcurrentHashMap

class Semaphore (val permits: Int) {

  private val semaphores = new ConcurrentHashMap[String, String]()

  def tryAcquire(spec: String): Boolean = {
    if (semaphores.size() < permits && !semaphores.containsKey(spec)) {
      semaphores.put(spec, "")
      true
    } else {
      false
    }
  }

  def isActive(spec: String): Boolean = semaphores.containsKey(spec)

  def release(spec: String) = semaphores.remove(spec)

  def availablePermits(): Int = {
    permits - semaphores.size()
  }

  def inUse(): Int = semaphores.size()

  def activeSpecs() = {
    semaphores.keySet()
  }
}
