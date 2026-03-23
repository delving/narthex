package services

import play.api.Logger

/** Global flag to enable/disable Fuseki reads and writes.
  *
  * When writes are disabled, all SPARQL update calls become no-ops (log-only).
  * When reads are disabled, ASK queries become no-ops (return false/empty).
  * This allows running without Fuseki while keeping the existing code paths.
  *
  * Set via config:
  *   narthex.fuseki.reads-enabled = false
  *   narthex.fuseki.writes-enabled = false
  */
object GlobalFusekiWrites {
  private val logger = Logger(getClass)

  @volatile private var writesEnabled: Boolean = true
  @volatile private var readsEnabled: Boolean = true

  def isWriteEnabled: Boolean = writesEnabled
  def isReadEnabled: Boolean = readsEnabled

  def setWrites(enabled: Boolean): Unit = {
    writesEnabled = enabled
    if (enabled) {
      logger.info("Fuseki writes ENABLED")
    } else {
      logger.warn("Fuseki writes DISABLED - all SPARQL updates will be skipped")
    }
  }

  def setReads(enabled: Boolean): Unit = {
    readsEnabled = enabled
    if (enabled) {
      logger.info("Fuseki reads ENABLED")
    } else {
      logger.warn("Fuseki reads DISABLED - all SPARQL queries will return empty/false")
    }
  }

  def enableWrites(): Unit = setWrites(true)
  def disableWrites(): Unit = setWrites(false)
  def enableReads(): Unit = setReads(true)
  def disableReads(): Unit = setReads(false)
}
