//===========================================================================
//    Copyright 2014 Delving B.V.
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

package harvest

import java.io._
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.zip.GZIPOutputStream

import dataset.SourceRepo.{IdFilter, SourceFacts}
import organization.OrgContext
import play.api.Logger
import record.PocketParser
import record.PocketParser._
import services.FileHandling.createWriter
import services.ProgressReporter

import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.control.NonFatal

/**
 * Background pocket writer that processes harvest pages concurrently.
 *
 * During harvest, pages are submitted via addPage() and processed in the background.
 * The writer parses each page into individual records (pockets) and writes them
 * to a compressed file. It also collects record IDs for the .ids file.
 *
 * @param outputFile The file to write pockets to (will be gzipped)
 * @param sourceFacts Record structure info (recordRoot, uniqueId)
 * @param idFilter Filter to apply to record IDs
 * @param orgContext Organization context for parsing
 * @param ec Execution context for background processing
 */
class PocketWriter(
    outputFile: File,
    sourceFacts: SourceFacts,
    idFilter: IdFilter,
    orgContext: OrgContext
)(implicit ec: ExecutionContext) {

  private val logger = Logger(getClass)

  // Page queue for background processing
  private val pageQueue = new ConcurrentLinkedQueue[String]()

  // Signals that no more pages will be added
  private val finished = new AtomicBoolean(false)

  // Latch to wait for processing completion
  private val completionLatch = new CountDownLatch(1)

  // Track record count
  private val recordCount = new AtomicInteger(0)

  // Collect record IDs (thread-safe)
  private val collectedIds = new ConcurrentLinkedQueue[String]()

  // Track any error that occurred during processing
  @volatile private var processingError: Option[Throwable] = None

  // Start background processing immediately
  private val processingFuture: Future[Int] = Future {
    processPages()
  }

  /**
   * Add a page of XML records to be processed.
   * This method returns immediately; processing happens in the background.
   */
  def addPage(pageContent: String): Unit = {
    if (finished.get()) {
      throw new IllegalStateException("Cannot add pages after finish() has been called")
    }
    pageQueue.offer(pageContent)
  }

  /**
   * Signal that no more pages will be added.
   * Call this when harvest is complete.
   */
  def finish(): Unit = {
    finished.set(true)
  }

  /**
   * Wait for all pages to be processed and return the record count.
   * Must call finish() before calling this method.
   *
   * @param timeoutMs Maximum time to wait in milliseconds
   * @return Number of records written, or throws if an error occurred
   */
  def awaitCompletion(timeoutMs: Long = 600000): Int = {
    if (!finished.get()) {
      throw new IllegalStateException("Must call finish() before awaitCompletion()")
    }

    import java.util.concurrent.TimeUnit
    if (!completionLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
      throw new RuntimeException(s"Pocket writer timed out after ${timeoutMs}ms")
    }

    processingError.foreach(throw _)
    recordCount.get()
  }

  /**
   * Get the output file path.
   */
  def getOutputFile: File = outputFile

  /**
   * Get the collected record IDs.
   * Only valid after awaitCompletion() returns successfully.
   */
  def getCollectedIds: Set[String] = {
    import scala.jdk.CollectionConverters._
    collectedIds.asScala.toSet
  }

  /**
   * Background processing loop.
   */
  private def processPages(): Int = {
    var writer: BufferedWriter = null

    try {
      // Create output stream with gzip compression
      val gzOut = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(outputFile), 65536))
      writer = createWriter(gzOut)

      // Write opening tag
      writer.write(s"<$POCKET_LIST>\n")

      val parser = new PocketParser(sourceFacts, idFilter, orgContext)
      val progress = ProgressReporter() // Dummy progress reporter

      // Process pages until finished and queue is empty
      while (!finished.get() || !pageQueue.isEmpty) {
        val page = pageQueue.poll()

        if (page != null) {
          // Parse this page and write pockets
          try {
            val pageSource = Source.fromString(page)
            try {
              parser.parse(pageSource, Set.empty, { pocket =>
                writer.write(pocket.getText)
                collectedIds.offer(pocket.id)
                recordCount.incrementAndGet()
              }, progress)
            } finally {
              pageSource.close()
            }
          } catch {
            case NonFatal(e) =>
              logger.error(s"Error parsing harvest page: ${e.getMessage}", e)
              // Continue processing other pages
          }
        } else {
          // Queue is empty but not finished yet, wait a bit
          Thread.sleep(10)
        }
      }

      // Write closing tag
      writer.write(s"</$POCKET_LIST>\n")
      writer.flush()

      val count = recordCount.get()
      logger.info(s"PocketWriter completed: wrote $count records to ${outputFile.getAbsolutePath} (${outputFile.length()} bytes)")
      count

    } catch {
      case NonFatal(e) =>
        logger.error(s"PocketWriter failed: ${e.getMessage}", e)
        processingError = Some(e)
        // Clean up partial file
        if (outputFile.exists()) {
          outputFile.delete()
        }
        0
    } finally {
      if (writer != null) {
        try { writer.close() } catch { case _: Exception => }
      }
      completionLatch.countDown()
    }
  }
}

object PocketWriter {
  /**
   * Create a PocketWriter for use during harvest.
   *
   * @param sourceDir The source directory where pockets.xml.gz will be written
   * @param sourceFacts Record structure info
   * @param idFilter Filter to apply to record IDs
   * @param orgContext Organization context
   * @param ec Execution context for background processing
   */
  def create(
      sourceDir: File,
      sourceFacts: SourceFacts,
      idFilter: IdFilter,
      orgContext: OrgContext
  )(implicit ec: ExecutionContext): PocketWriter = {
    val outputFile = new File(sourceDir, "pockets.xml.gz")
    new PocketWriter(outputFile, sourceFacts, idFilter, orgContext)
  }
}
