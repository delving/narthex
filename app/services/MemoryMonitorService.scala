//===========================================================================
//    Copyright 2026 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//===========================================================================
package services

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import akka.actor.ActorSystem
import play.api.{Configuration, Logging}
import play.api.libs.json._
import java.time.Instant
import java.time.format.DateTimeFormatter
import scala.collection.mutable

/**
 * Service to monitor JVM memory usage over time.
 * Samples memory every N seconds and tracks min/max/avg.
 */
@Singleton
class MemoryMonitorService @Inject()(
  actorSystem: ActorSystem,
  config: Configuration
)(implicit ec: ExecutionContext) extends Logging {

  private val runtime = Runtime.getRuntime
  private val MB = 1024L * 1024L

  // Configuration
  private val sampleIntervalSeconds = config.getOptional[Int]("memory.sampleIntervalSeconds").getOrElse(60)
  private val maxSamples = config.getOptional[Int]("memory.maxSamples").getOrElse(1440) // 24 hours at 1 min intervals

  // Stats tracking
  private val samples = mutable.Queue[(Long, MemorySample)]()
  private var minHeapUsed: Long = Long.MaxValue
  private var maxHeapUsed: Long = 0L
  private var totalHeapUsed: Long = 0L
  private var sampleCount: Long = 0L
  private var startTime: Long = System.currentTimeMillis()

  case class MemorySample(
    heapUsed: Long,
    heapMax: Long,
    heapFree: Long,
    nonHeapUsed: Long
  )

  case class MemoryStats(
    currentHeapUsedMB: Long,
    currentHeapMaxMB: Long,
    currentHeapFreeMB: Long,
    minHeapUsedMB: Long,
    maxHeapUsedMB: Long,
    avgHeapUsedMB: Long,
    sampleCount: Long,
    monitoringDurationMinutes: Long,
    lastSamples: Seq[SamplePoint]
  )

  case class SamplePoint(
    timestamp: String,
    heapUsedMB: Long,
    heapFreeMB: Long
  )

  object MemoryStats {
    implicit val samplePointWrites: Writes[SamplePoint] = Json.writes[SamplePoint]
    implicit val writes: Writes[MemoryStats] = Json.writes[MemoryStats]
  }

  // Start sampling on initialization
  startSampling()

  private def startSampling(): Unit = {
    logger.info(s"Starting memory monitor: sampling every $sampleIntervalSeconds seconds, keeping $maxSamples samples")

    actorSystem.scheduler.scheduleAtFixedRate(
      initialDelay = 0.seconds,
      interval = sampleIntervalSeconds.seconds
    ) { () =>
      try {
        takeSample()
      } catch {
        case e: Exception =>
          logger.warn(s"Error sampling memory: ${e.getMessage}")
      }
    }
  }

  private def takeSample(): Unit = {
    val totalMemory = runtime.totalMemory()
    val freeMemory = runtime.freeMemory()
    val maxMemory = runtime.maxMemory()
    val usedMemory = totalMemory - freeMemory

    // Get non-heap memory (metaspace, etc.)
    val memoryMXBean = java.lang.management.ManagementFactory.getMemoryMXBean
    val nonHeapUsed = memoryMXBean.getNonHeapMemoryUsage.getUsed

    val sample = MemorySample(
      heapUsed = usedMemory,
      heapMax = maxMemory,
      heapFree = freeMemory,
      nonHeapUsed = nonHeapUsed
    )

    synchronized {
      val now = System.currentTimeMillis()
      samples.enqueue((now, sample))

      // Keep only maxSamples
      while (samples.size > maxSamples) {
        samples.dequeue()
      }

      // Update stats
      if (usedMemory < minHeapUsed) minHeapUsed = usedMemory
      if (usedMemory > maxHeapUsed) maxHeapUsed = usedMemory
      totalHeapUsed += usedMemory
      sampleCount += 1

      // Log periodically (every 10 samples)
      if (sampleCount % 10 == 0) {
        logger.info(f"Memory: used=${usedMemory / MB}MB, max=${maxMemory / MB}MB, " +
          f"min=${minHeapUsed / MB}MB, peak=${maxHeapUsed / MB}MB, avg=${totalHeapUsed / sampleCount / MB}MB")
      }
    }
  }

  /**
   * Get current memory statistics.
   */
  def getStats: MemoryStats = synchronized {
    val totalMemory = runtime.totalMemory()
    val freeMemory = runtime.freeMemory()
    val maxMemory = runtime.maxMemory()
    val usedMemory = totalMemory - freeMemory

    val durationMinutes = (System.currentTimeMillis() - startTime) / 60000

    // Get last 60 samples for charting (1 hour at 1 min intervals)
    val formatter = DateTimeFormatter.ISO_INSTANT
    val lastSamples = samples.takeRight(60).map { case (ts, s) =>
      SamplePoint(
        timestamp = formatter.format(Instant.ofEpochMilli(ts)),
        heapUsedMB = s.heapUsed / MB,
        heapFreeMB = s.heapFree / MB
      )
    }.toSeq

    MemoryStats(
      currentHeapUsedMB = usedMemory / MB,
      currentHeapMaxMB = maxMemory / MB,
      currentHeapFreeMB = freeMemory / MB,
      minHeapUsedMB = if (minHeapUsed == Long.MaxValue) 0 else minHeapUsed / MB,
      maxHeapUsedMB = maxHeapUsed / MB,
      avgHeapUsedMB = if (sampleCount > 0) totalHeapUsed / sampleCount / MB else 0,
      sampleCount = sampleCount,
      monitoringDurationMinutes = durationMinutes,
      lastSamples = lastSamples
    )
  }

  /**
   * Reset statistics (keeps sampling).
   */
  def resetStats(): Unit = synchronized {
    samples.clear()
    minHeapUsed = Long.MaxValue
    maxHeapUsed = 0L
    totalHeapUsed = 0L
    sampleCount = 0L
    startTime = System.currentTimeMillis()
    logger.info("Memory statistics reset")
  }

  /**
   * Force garbage collection and return memory freed.
   */
  def forceGC(): Long = {
    val beforeUsed = runtime.totalMemory() - runtime.freeMemory()
    System.gc()
    Thread.sleep(100) // Give GC a moment
    val afterUsed = runtime.totalMemory() - runtime.freeMemory()
    val freed = beforeUsed - afterUsed
    logger.info(f"GC freed ${freed / MB}MB (before=${beforeUsed / MB}MB, after=${afterUsed / MB}MB)")
    freed / MB
  }
}
