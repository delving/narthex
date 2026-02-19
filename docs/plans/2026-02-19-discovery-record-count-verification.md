# Discovery Record Count Verification — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add background verification of OAI-PMH set record counts so discovery only shows sets that actually have records, with cached results and a sidebar badge.

**Architecture:** Extends the existing discovery module with a counts cache (JSON file per source), a `SetCountVerifier` class that uses `WSClient` to call `ListIdentifiers` and read `completeListSize`, and new controller endpoints for triggering/polling verification status. Frontend adds records column, empty sets panel, verification controls, and sidebar badge.

**Tech Stack:** Scala/Play 2.8, Akka actors, WSClient, AngularJS 1.3, JSON file storage

---

### Task 1: Add counts cache read/write to OaiSourceRepo

**Files:**
- Modify: `app/discovery/OaiSourceRepo.scala`
- Modify: `app/discovery/OaiSourceConfig.scala`
- Test: `test/discovery/SetCountCacheSpec.scala`

**Step 1: Add cache data models to OaiSourceConfig.scala**

Add after the `IgnoreRequest` case class (line ~213):

```scala
/**
 * Cached record counts for a source's sets.
 */
case class SetCountCache(
  sourceId: String,
  lastVerified: DateTime,
  counts: Map[String, Int],
  errors: Map[String, String],
  summary: CountSummary
)

case class CountSummary(
  totalSets: Int,
  newWithRecords: Int,
  empty: Int
)

implicit val countSummaryFormat: Format[CountSummary] = Json.format[CountSummary]
implicit val setCountCacheFormat: Format[SetCountCache] = Json.format[SetCountCache]
```

**Step 2: Add cache read/write methods to OaiSourceRepo.scala**

Add after the `updateLastChecked` method (line ~156):

```scala
/**
 * Load cached record counts for a source.
 */
def loadCountsCache(sourceId: String): Option[SetCountCache] = {
  val cacheFile = new File(sourcesDir, s"$sourceId-counts.json")
  if (!cacheFile.exists()) {
    None
  } else {
    try {
      val content = FileUtils.readFileToString(cacheFile, "UTF-8")
      Some(Json.parse(content).as[SetCountCache])
    } catch {
      case e: Exception =>
        logger.error(s"Error reading counts cache for $sourceId: ${e.getMessage}", e)
        None
    }
  }
}

/**
 * Save record counts cache for a source.
 */
def saveCountsCache(cache: SetCountCache): Unit = {
  val cacheFile = new File(sourcesDir, s"${cache.sourceId}-counts.json")
  val json = Json.prettyPrint(Json.toJson(cache))
  FileUtils.writeStringToFile(cacheFile, json, "UTF-8")
  logger.info(s"Saved counts cache for ${cache.sourceId}: ${cache.summary.newWithRecords} with records, ${cache.summary.empty} empty")
}
```

**Step 3: Write test for cache round-trip**

Create `test/discovery/SetCountCacheSpec.scala`:

```scala
package discovery

import java.io.File
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.scalatest.flatspec._
import org.scalatest.matchers._
import org.scalatest.BeforeAndAfterEach
import discovery.OaiSourceConfig._

class SetCountCacheSpec extends AnyFlatSpec with should.Matchers with BeforeAndAfterEach {

  private val testDir = new File(System.getProperty("java.io.tmpdir"), "narthex-test-" + System.currentTimeMillis())

  override def beforeEach(): Unit = {
    testDir.mkdirs()
  }

  override def afterEach(): Unit = {
    FileUtils.deleteDirectory(testDir)
  }

  "OaiSourceRepo" should "save and load counts cache" in {
    val repo = new OaiSourceRepo(testDir)
    val cache = SetCountCache(
      sourceId = "test-source",
      lastVerified = DateTime.now(),
      counts = Map("set-a" -> 100, "set-b" -> 0, "set-c" -> 42),
      errors = Map("set-d" -> "HTTP 500"),
      summary = CountSummary(totalSets = 4, newWithRecords = 2, empty = 1)
    )

    repo.saveCountsCache(cache)
    val loaded = repo.loadCountsCache("test-source")

    loaded shouldBe defined
    loaded.get.sourceId shouldBe "test-source"
    loaded.get.counts("set-a") shouldBe 100
    loaded.get.counts("set-b") shouldBe 0
    loaded.get.summary.newWithRecords shouldBe 2
    loaded.get.errors("set-d") shouldBe "HTTP 500"
  }

  it should "return None for missing cache" in {
    val repo = new OaiSourceRepo(testDir)
    repo.loadCountsCache("nonexistent") shouldBe None
  }
}
```

**Step 4: Run tests**

Run: `make compile && sbt "testOnly discovery.SetCountCacheSpec"`
Expected: PASS

**Step 5: Commit**

```bash
git add app/discovery/OaiSourceConfig.scala app/discovery/OaiSourceRepo.scala test/discovery/SetCountCacheSpec.scala
git commit -m "feat(discovery): add counts cache data model and read/write"
```

---

### Task 2: Add ListIdentifiers completeListSize parser

**Files:**
- Modify: `app/discovery/OaiListSetsParser.scala`
- Test: `test/discovery/ListIdentifiersParserSpec.scala`

**Step 1: Write test for completeListSize parsing**

Create `test/discovery/ListIdentifiersParserSpec.scala`:

```scala
package discovery

import org.scalatest.flatspec._
import org.scalatest.matchers._

class ListIdentifiersParserSpec extends AnyFlatSpec with should.Matchers {

  "OaiListSetsParser" should "parse completeListSize from resumptionToken" in {
    val xml = """<?xml version="1.0" encoding="UTF-8"?>
      |<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
      |  <responseDate>2026-02-19T12:00:00Z</responseDate>
      |  <request verb="ListIdentifiers">http://example.com/oai</request>
      |  <ListIdentifiers>
      |    <header><identifier>oai:example:1</identifier></header>
      |    <resumptionToken completeListSize="1523" cursor="0">token123</resumptionToken>
      |  </ListIdentifiers>
      |</OAI-PMH>""".stripMargin

    val result = OaiListSetsParser.parseCompleteListSize(xml)
    result shouldBe Right(1523)
  }

  it should "return 0 for empty set with completeListSize=0" in {
    val xml = """<?xml version="1.0" encoding="UTF-8"?>
      |<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
      |  <responseDate>2026-02-19T12:00:00Z</responseDate>
      |  <request verb="ListIdentifiers">http://example.com/oai</request>
      |  <ListIdentifiers>
      |    <resumptionToken completeListSize="0" cursor="0"></resumptionToken>
      |  </ListIdentifiers>
      |</OAI-PMH>""".stripMargin

    val result = OaiListSetsParser.parseCompleteListSize(xml)
    result shouldBe Right(0)
  }

  it should "return 0 when no resumptionToken and no headers" in {
    val xml = """<?xml version="1.0" encoding="UTF-8"?>
      |<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
      |  <responseDate>2026-02-19T12:00:00Z</responseDate>
      |  <request verb="ListIdentifiers">http://example.com/oai</request>
      |  <ListIdentifiers>
      |  </ListIdentifiers>
      |</OAI-PMH>""".stripMargin

    val result = OaiListSetsParser.parseCompleteListSize(xml)
    result shouldBe Right(0)
  }

  it should "count headers when no completeListSize attribute" in {
    val xml = """<?xml version="1.0" encoding="UTF-8"?>
      |<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
      |  <responseDate>2026-02-19T12:00:00Z</responseDate>
      |  <request verb="ListIdentifiers">http://example.com/oai</request>
      |  <ListIdentifiers>
      |    <header><identifier>oai:example:1</identifier></header>
      |    <header><identifier>oai:example:2</identifier></header>
      |  </ListIdentifiers>
      |</OAI-PMH>""".stripMargin

    val result = OaiListSetsParser.parseCompleteListSize(xml)
    result shouldBe Right(2)
  }

  it should "return error for OAI-PMH error response" in {
    val xml = """<?xml version="1.0" encoding="UTF-8"?>
      |<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
      |  <responseDate>2026-02-19T12:00:00Z</responseDate>
      |  <error code="noRecordsMatch">No records match</error>
      |</OAI-PMH>""".stripMargin

    val result = OaiListSetsParser.parseCompleteListSize(xml)
    // noRecordsMatch means 0 records, not an error
    result shouldBe Right(0)
  }
}
```

**Step 2: Add parsing method to OaiListSetsParser**

Add a companion object method to `app/discovery/OaiListSetsParser.scala` (after the class, before file end):

```scala
object OaiListSetsParser {
  /**
   * Parse completeListSize from a ListIdentifiers XML response.
   *
   * Strategies (in order):
   * 1. Read completeListSize attribute from resumptionToken
   * 2. If no resumptionToken or no attribute, count header elements
   * 3. noRecordsMatch error → 0
   * 4. Other OAI-PMH errors → Left(error)
   */
  def parseCompleteListSize(xmlString: String): Either[String, Int] = {
    try {
      val xml = scala.xml.XML.loadString(xmlString)

      // Check for OAI-PMH error
      val errorNode = xml \\ "error"
      if (errorNode.nonEmpty) {
        val errorCode = (errorNode.head \ "@code").text
        if (errorCode == "noRecordsMatch") {
          return Right(0)
        }
        return Left(s"OAI-PMH error [$errorCode]: ${errorNode.head.text}")
      }

      // Try resumptionToken completeListSize attribute
      val resumptionToken = xml \\ "resumptionToken"
      if (resumptionToken.nonEmpty) {
        val sizeAttr = (resumptionToken.head \ "@completeListSize").text.trim
        if (sizeAttr.nonEmpty) {
          return Right(sizeAttr.toInt)
        }
      }

      // Fallback: count header elements
      val headers = xml \\ "header"
      Right(headers.length)

    } catch {
      case e: Exception =>
        Left(s"Failed to parse ListIdentifiers response: ${e.getMessage}")
    }
  }
}
```

**Step 3: Run tests**

Run: `make compile && sbt "testOnly discovery.ListIdentifiersParserSpec"`
Expected: PASS

**Step 4: Commit**

```bash
git add app/discovery/OaiListSetsParser.scala test/discovery/ListIdentifiersParserSpec.scala
git commit -m "feat(discovery): parse completeListSize from ListIdentifiers response"
```

---

### Task 3: Implement SetCountVerifier

**Files:**
- Create: `app/discovery/SetCountVerifier.scala`
- Test: `test/discovery/SetCountVerifierSpec.scala`

**Step 1: Create SetCountVerifier class**

Create `app/discovery/SetCountVerifier.scala`:

```scala
package discovery

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.ws.WSClient
import discovery.OaiSourceConfig._

/**
 * Verifies record counts for OAI-PMH sets by calling ListIdentifiers
 * and reading completeListSize from the resumptionToken.
 *
 * Runs sequentially with throttling to avoid overwhelming the endpoint.
 */
@Singleton
class SetCountVerifier @Inject()(
  wsClient: WSClient
)(implicit ec: ExecutionContext) {

  private val logger = Logger(getClass)
  private val REQUEST_TIMEOUT = 30.seconds
  private val DEFAULT_DELAY_MS = 500

  // Track running verifications by sourceId
  @volatile private var runningJobs: Map[String, VerificationProgress] = Map.empty

  case class VerificationProgress(
    total: Int,
    checked: Int,
    withRecords: Int,
    errors: Int,
    status: String // "running", "complete", "failed"
  )

  def getProgress(sourceId: String): VerificationProgress = {
    runningJobs.getOrElse(sourceId, VerificationProgress(0, 0, 0, 0, "idle"))
  }

  /**
   * Check if a verification is already running for this source.
   */
  def isRunning(sourceId: String): Boolean = {
    runningJobs.get(sourceId).exists(_.status == "running")
  }

  /**
   * Verify record counts for a list of setSpecs.
   *
   * @param sourceId    Source identifier (for progress tracking)
   * @param baseUrl     OAI-PMH base URL
   * @param prefix      Metadata prefix for ListIdentifiers
   * @param setSpecs    Set specs to check
   * @param delayMs     Delay between requests in milliseconds
   * @param onProgress  Callback for progress updates (every 50 sets and at completion)
   * @return Future with map of setSpec -> count and map of setSpec -> error
   */
  def verify(
    sourceId: String,
    baseUrl: String,
    prefix: String,
    setSpecs: List[String],
    delayMs: Int = DEFAULT_DELAY_MS,
    onProgress: (Map[String, Int], Map[String, String]) => Unit = (_, _) => ()
  ): Future[(Map[String, Int], Map[String, String])] = {

    if (isRunning(sourceId)) {
      return Future.failed(new IllegalStateException(s"Verification already running for $sourceId"))
    }

    val total = setSpecs.size
    runningJobs = runningJobs + (sourceId -> VerificationProgress(total, 0, 0, 0, "running"))

    logger.info(s"Starting record count verification for $sourceId: $total sets to check")

    // Process sets sequentially with delay
    val resultFuture = setSpecs.zipWithIndex.foldLeft(
      Future.successful((Map.empty[String, Int], Map.empty[String, String]))
    ) { case (accFuture, (setSpec, index)) =>
      accFuture.flatMap { case (counts, errors) =>
        // Delay between requests
        val delayFuture = if (index > 0) {
          akka.pattern.after(delayMs.milliseconds, using = akka.actor.ActorSystem("verification-delay").scheduler)(Future.successful(()))
        } else {
          Future.successful(())
        }

        delayFuture.flatMap { _ =>
          fetchRecordCount(baseUrl, prefix, setSpec).map { result =>
            val (newCounts, newErrors) = result match {
              case Right(count) => (counts + (setSpec -> count), errors)
              case Left(error) => (counts, errors + (setSpec -> error))
            }

            val checked = index + 1
            val withRecords = newCounts.values.count(_ > 0)
            val errorCount = newErrors.size

            runningJobs = runningJobs + (sourceId ->
              VerificationProgress(total, checked, withRecords, errorCount, "running"))

            // Progress callback every 50 sets
            if (checked % 50 == 0 || checked == total) {
              logger.info(s"Verification progress for $sourceId: $checked/$total (${withRecords} with records, $errorCount errors)")
              onProgress(newCounts, newErrors)
            }

            (newCounts, newErrors)
          }
        }
      }
    }

    resultFuture.map { result =>
      val (counts, errors) = result
      val withRecords = counts.values.count(_ > 0)
      runningJobs = runningJobs + (sourceId ->
        VerificationProgress(total, total, withRecords, errors.size, "complete"))
      logger.info(s"Verification complete for $sourceId: ${withRecords} with records, ${counts.values.count(_ == 0)} empty, ${errors.size} errors")
      result
    }.recover { case e: Exception =>
      logger.error(s"Verification failed for $sourceId: ${e.getMessage}", e)
      runningJobs = runningJobs + (sourceId ->
        VerificationProgress(total, 0, 0, 0, "failed"))
      (Map.empty[String, Int], Map.empty[String, String])
    }
  }

  /**
   * Fetch record count for a single set via ListIdentifiers.
   */
  private def fetchRecordCount(baseUrl: String, prefix: String, setSpec: String): Future[Either[String, Int]] = {
    val separator = if (baseUrl.contains("?")) "&" else "?"
    val url = s"${baseUrl.stripSuffix("/")}${separator}verb=ListIdentifiers&metadataPrefix=$prefix&set=$setSpec"

    wsClient.url(url)
      .withRequestTimeout(REQUEST_TIMEOUT)
      .get()
      .map { response =>
        if (response.status == 200) {
          OaiListSetsParser.parseCompleteListSize(response.body)
        } else {
          Left(s"HTTP ${response.status}: ${response.statusText}")
        }
      }
      .recover { case e: Exception =>
        Left(s"Request failed: ${e.getMessage}")
      }
  }
}
```

**Step 2: Write test**

Create `test/discovery/SetCountVerifierSpec.scala`:

```scala
package discovery

import org.scalatest.flatspec._
import org.scalatest.matchers._

/**
 * Unit tests for the parsing logic used by SetCountVerifier.
 * Integration testing with real HTTP is done manually against the endpoint.
 */
class SetCountVerifierSpec extends AnyFlatSpec with should.Matchers {

  "SetCountVerifier" should "parse record counts from various ListIdentifiers responses" in {
    // Test via the companion object parser (tested in ListIdentifiersParserSpec)
    val emptyResponse = """<?xml version="1.0" encoding="UTF-8"?>
      |<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
      |  <ListIdentifiers>
      |    <resumptionToken completeListSize="0" cursor="0"></resumptionToken>
      |  </ListIdentifiers>
      |</OAI-PMH>""".stripMargin

    OaiListSetsParser.parseCompleteListSize(emptyResponse) shouldBe Right(0)
  }
}
```

**Step 3: Run tests**

Run: `make compile && sbt "testOnly discovery.SetCountVerifierSpec"`
Expected: PASS

**Step 4: Commit**

```bash
git add app/discovery/SetCountVerifier.scala test/discovery/SetCountVerifierSpec.scala
git commit -m "feat(discovery): add SetCountVerifier for background record count checking"
```

---

### Task 4: Update DiscoveryResult model with empty sets and counts

**Files:**
- Modify: `app/discovery/OaiSourceConfig.scala`

**Step 1: Add fields to DiscoveredSet**

In `OaiSourceConfig.scala`, update `DiscoveredSet` (line ~129):

```scala
case class DiscoveredSet(
  setSpec: String,
  normalizedSpec: String,
  setName: String,
  title: Option[String],
  description: Option[String],
  status: String,
  matchedMappingRule: Option[MappingRule],
  recordCount: Option[Int] = None,
  countVerifiedAt: Option[DateTime] = None
)
```

**Step 2: Add fields to DiscoveryResult**

Update `DiscoveryResult` (line ~153):

```scala
case class DiscoveryResult(
  sourceId: String,
  sourceName: String,
  timestamp: DateTime,
  totalSets: Int,
  newSets: List[DiscoveredSet],
  existingSets: List[DiscoveredSet],
  ignoredSets: List[DiscoveredSet],
  emptySets: List[DiscoveredSet] = List.empty,
  errors: List[String],
  countsLastVerified: Option[DateTime] = None,
  countsAvailable: Boolean = false
)
```

**Step 3: Run compile**

Run: `make compile`
Expected: Success (default values ensure backward compat)

**Step 4: Commit**

```bash
git add app/discovery/OaiSourceConfig.scala
git commit -m "feat(discovery): add recordCount, emptySets and countsLastVerified to models"
```

---

### Task 5: Merge cached counts into discovery results

**Files:**
- Modify: `app/discovery/DatasetDiscoveryService.scala`

**Step 1: Update discoverSets to load and merge cache**

In `DatasetDiscoveryService.scala`, update the `discoverSets` method. After the existing classification logic (around line ~118, after the `foldLeft`), replace the result construction:

```scala
// Load cached counts
val countsCache = sourceRepo.loadCountsCache(sourceId)
val cachedCounts = countsCache.map(_.counts).getOrElse(Map.empty)
val countsVerifiedAt = countsCache.map(_.lastVerified)

// Enrich sets with cached record counts
val enrichedSets = discoveredSets.map { set =>
  cachedCounts.get(set.setSpec) match {
    case Some(count) =>
      set.copy(
        recordCount = Some(count),
        countVerifiedAt = countsVerifiedAt
      )
    case None => set
  }
}

// Re-classify: sets with cached count of 0 become "empty"
val (newSets, existing, ignored, empty) = enrichedSets.foldLeft(
  (List.empty[DiscoveredSet], List.empty[DiscoveredSet],
   List.empty[DiscoveredSet], List.empty[DiscoveredSet])
) {
  case ((n, e, i, em), set) =>
    set.status match {
      case "ignored" => (n, e, i :+ set, em)
      case "existing" => (n, e :+ set, i, em)
      case "new" if set.recordCount.contains(0) =>
        (n, e, i, em :+ set.copy(status = "empty"))
      case _ => (n :+ set, e, i, em)
    }
}

logger.info(s"Discovery result for ${source.name}: ${newSets.size} new, ${existing.size} existing, ${empty.size} empty, ${ignored.size} ignored")

Right(DiscoveryResult(
  sourceId = sourceId,
  sourceName = source.name,
  timestamp = timestamp,
  totalSets = enrichedSets.length,
  newSets = newSets,
  existingSets = existing,
  ignoredSets = ignored,
  emptySets = empty,
  errors = List.empty,
  countsLastVerified = countsVerifiedAt,
  countsAvailable = countsCache.isDefined
))
```

**Step 2: Run compile**

Run: `make compile`
Expected: Success

**Step 3: Commit**

```bash
git add app/discovery/DatasetDiscoveryService.scala
git commit -m "feat(discovery): merge cached record counts into discovery results"
```

---

### Task 6: Add verify/verify-status controller endpoints

**Files:**
- Modify: `app/controllers/DiscoveryController.scala`
- Modify: `conf/routes`

**Step 1: Inject SetCountVerifier into DiscoveryController**

Update the controller constructor:

```scala
@Singleton
class DiscoveryController @Inject()(
  cc: ControllerComponents,
  discoveryService: DatasetDiscoveryService,
  setCountVerifier: SetCountVerifier
)(implicit ec: ExecutionContext) extends AbstractController(cc) {
```

**Step 2: Add verify endpoint**

Add after `previewSpecTransform` method:

```scala
/**
 * Start background record count verification for a source.
 */
def startVerification(id: String): Action[AnyContent] = Action { request =>
  sourceRepo.getSource(id) match {
    case None =>
      NotFound(Json.obj("error" -> s"Source not found: $id"))
    case Some(source) =>
      if (setCountVerifier.isRunning(id)) {
        Conflict(Json.obj("error" -> "Verification already running", "status" -> "running"))
      } else {
        // Get current discovery to find which sets need checking
        // We check all non-existing, non-ignored sets
        discoveryService.discoverSets(id).map {
          case Right(result) =>
            // Combine new and empty sets (both need re-checking)
            val setsToCheck = (result.newSets ++ result.emptySets).map(_.setSpec)

            if (setsToCheck.isEmpty) {
              Ok(Json.obj("status" -> "complete", "totalToCheck" -> 0, "message" -> "No sets to verify"))
            } else {
              // Start verification in background
              setCountVerifier.verify(
                sourceId = id,
                baseUrl = source.url,
                prefix = source.defaultMetadataPrefix,
                setSpecs = setsToCheck,
                delayMs = 500,
                onProgress = (counts, errors) => {
                  // Save intermediate results
                  val withRecords = counts.values.count(_ > 0)
                  val emptyCount = counts.values.count(_ == 0)
                  val cache = OaiSourceConfig.SetCountCache(
                    sourceId = id,
                    lastVerified = org.joda.time.DateTime.now(),
                    counts = counts,
                    errors = errors,
                    summary = OaiSourceConfig.CountSummary(
                      totalSets = counts.size + errors.size,
                      newWithRecords = withRecords,
                      empty = emptyCount
                    )
                  )
                  sourceRepo.saveCountsCache(cache)
                }
              )

              Ok(Json.obj("status" -> "started", "totalToCheck" -> setsToCheck.size))
            }
          case Left(error) =>
            BadRequest(Json.obj("error" -> s"Discovery failed: $error"))
        }

        // Return immediately — verification runs in background
        // Note: we need to handle the Future properly
        Ok(Json.obj("status" -> "started", "message" -> "Verification starting"))
      }
  }
}

/**
 * Get verification progress for a source.
 */
def getVerificationStatus(id: String): Action[AnyContent] = Action { request =>
  val progress = setCountVerifier.getProgress(id)
  Ok(Json.obj(
    "status" -> progress.status,
    "total" -> progress.total,
    "checked" -> progress.checked,
    "withRecords" -> progress.withRecords,
    "errors" -> progress.errors
  ))
}
```

**Note:** The `startVerification` endpoint has a problem — it returns a response but also starts a Future-based discovery internally. This needs to be refactored so the verification launch is fire-and-forget. The actual approach: run discovery first to get the set list, then kick off verification. Since discovery itself is async, make `startVerification` an `Action.async`:

```scala
def startVerification(id: String): Action[AnyContent] = Action.async { request =>
  sourceRepo.getSource(id) match {
    case None =>
      Future.successful(NotFound(Json.obj("error" -> s"Source not found: $id")))
    case Some(source) =>
      if (setCountVerifier.isRunning(id)) {
        Future.successful(Conflict(Json.obj("error" -> "Verification already running")))
      } else {
        discoveryService.discoverSets(id).map {
          case Right(result) =>
            val setsToCheck = (result.newSets ++ result.emptySets).map(_.setSpec)
            if (setsToCheck.isEmpty) {
              Ok(Json.obj("status" -> "complete", "totalToCheck" -> 0))
            } else {
              // Fire and forget — verification runs in background
              setCountVerifier.verify(
                sourceId = id,
                baseUrl = source.url,
                prefix = source.defaultMetadataPrefix,
                setSpecs = setsToCheck,
                delayMs = 500,
                onProgress = (counts, errors) => {
                  val withRecords = counts.values.count(_ > 0)
                  val emptyCount = counts.values.count(_ == 0)
                  sourceRepo.saveCountsCache(SetCountCache(
                    sourceId = id,
                    lastVerified = org.joda.time.DateTime.now(),
                    counts = counts,
                    errors = errors,
                    summary = CountSummary(counts.size + errors.size, withRecords, emptyCount)
                  ))
                }
              )
              Ok(Json.obj("status" -> "started", "totalToCheck" -> setsToCheck.size))
            }
          case Left(error) =>
            BadRequest(Json.obj("error" -> error))
        }
      }
  }
}
```

**Step 3: Add routes**

Add to `conf/routes` after the existing discovery routes:

```
+ nocsrf
POST        /narthex/app/discovery/sources/:id/verify                        controllers.DiscoveryController.startVerification(id)
GET         /narthex/app/discovery/sources/:id/verify-status                  controllers.DiscoveryController.getVerificationStatus(id)
```

**Step 4: Run compile**

Run: `make compile`
Expected: Success

**Step 5: Commit**

```bash
git add app/controllers/DiscoveryController.scala conf/routes
git commit -m "feat(discovery): add verify and verify-status endpoints"
```

---

### Task 7: Enrich source list with newSetCount for sidebar badge

**Files:**
- Modify: `app/controllers/DiscoveryController.scala`

**Step 1: Update listSources to include cached counts info**

Replace the `listSources` method:

```scala
def listSources: Action[AnyContent] = Action { request =>
  val sources = sourceRepo.listSources()

  // Enrich each source with cached count info
  val enrichedSources = sources.map { source =>
    val cache = sourceRepo.loadCountsCache(source.id)
    val sourceJson = Json.toJson(source).as[JsObject]

    cache match {
      case Some(c) =>
        sourceJson ++ Json.obj(
          "newSetCount" -> c.summary.newWithRecords,
          "emptySetCount" -> c.summary.empty,
          "countsLastVerified" -> Json.toJson(c.lastVerified)
        )
      case None =>
        sourceJson ++ Json.obj(
          "newSetCount" -> JsNull,
          "emptySetCount" -> JsNull,
          "countsLastVerified" -> JsNull
        )
    }
  }

  Ok(JsArray(enrichedSources))
}
```

**Step 2: Run compile**

Run: `make compile`
Expected: Success

**Step 3: Commit**

```bash
git add app/controllers/DiscoveryController.scala
git commit -m "feat(discovery): enrich source list with cached newSetCount for sidebar badge"
```

---

### Task 8: Frontend — Add verification API calls to discovery service

**Files:**
- Modify: `app/assets/javascripts/discovery/discovery-services.js`

**Step 1: Add new API methods**

Add to the return object in `discovery-services.js`:

```javascript
// Record count verification
startVerification: function(sourceId) {
    return $http.post(baseUrl + '/sources/' + sourceId + '/verify')
        .then(function(r) { return r.data; });
},

getVerificationStatus: function(sourceId) {
    return $http.get(baseUrl + '/sources/' + sourceId + '/verify-status')
        .then(function(r) { return r.data; });
}
```

**Step 2: Commit**

```bash
git add app/assets/javascripts/discovery/discovery-services.js
git commit -m "feat(discovery): add verification API calls to frontend service"
```

---

### Task 9: Frontend — Add verification controls and progress to controller

**Files:**
- Modify: `app/assets/javascripts/discovery/discovery-controllers.js`

**Step 1: Add verification state and methods to DiscoveryCtrl**

Add after `$scope.importing = false;` (line ~29):

```javascript
$scope.verifying = false;
$scope.verifyProgress = null;
var verifyPollTimer = null;
```

Add after the `formatDate` function (line ~219), before the Initialize section:

```javascript
// Verification
$scope.startVerification = function() {
    if (!$scope.selectedSource || $scope.verifying) return;

    $scope.verifying = true;
    $scope.verifyProgress = { status: 'starting', checked: 0, total: 0 };

    discoveryService.startVerification($scope.selectedSource.id).then(function(result) {
        if (result.status === 'complete') {
            $scope.verifying = false;
            $scope.verifyProgress = null;
            $scope.discover(); // Refresh to show updated counts
        } else {
            $scope.verifyProgress = { status: 'running', checked: 0, total: result.totalToCheck };
            startVerifyPolling();
        }
    }, function(error) {
        $scope.verifying = false;
        $scope.verifyProgress = null;
        alert("Verification failed: " + (error.data ? error.data.error : "Unknown error"));
    });
};

function startVerifyPolling() {
    if (verifyPollTimer) return;
    verifyPollTimer = setInterval(function() {
        if (!$scope.selectedSource) {
            stopVerifyPolling();
            return;
        }
        discoveryService.getVerificationStatus($scope.selectedSource.id).then(function(status) {
            $scope.verifyProgress = status;
            if (status.status === 'complete' || status.status === 'idle') {
                stopVerifyPolling();
                $scope.verifying = false;
                $scope.discover(); // Refresh with new counts
            }
        });
    }, 3000);
}

function stopVerifyPolling() {
    if (verifyPollTimer) {
        clearInterval(verifyPollTimer);
        verifyPollTimer = null;
    }
}

// Clean up polling on scope destroy
$scope.$on('$destroy', function() {
    stopVerifyPolling();
});
```

**Step 2: Commit**

```bash
git add app/assets/javascripts/discovery/discovery-controllers.js
git commit -m "feat(discovery): add verification controls and polling to frontend controller"
```

---

### Task 10: Frontend — Update discovery.html template

**Files:**
- Modify: `public/templates/discovery.html`

**Step 1: Update summary bar to show 4 categories + verification info**

Replace the existing `well well-sm` div (line ~72-77):

```html
<div class="well well-sm">
    <strong>{{ discoveryResult.totalSets }}</strong> total sets |
    <strong class="text-success">{{ discoveryResult.newSets.length }}</strong> new |
    <strong class="text-info">{{ discoveryResult.existingSets.length }}</strong> existing |
    <strong class="text-muted">{{ discoveryResult.emptySets.length }}</strong> empty |
    <strong class="text-warning">{{ discoveryResult.ignoredSets.length }}</strong> ignored
    <span class="pull-right" ng-if="discoveryResult.countsAvailable">
        <small class="text-muted">
            <i class="fa fa-clock-o"></i> Counts verified: {{ formatDate(discoveryResult.countsLastVerified) }}
        </small>
    </span>
</div>

<!-- Verification Controls -->
<div style="margin-bottom: 15px;">
    <button class="btn btn-sm"
            ng-class="discoveryResult.countsAvailable ? 'btn-default' : 'btn-primary'"
            ng-click="startVerification()"
            ng-disabled="verifying">
        <i class="fa" ng-class="verifying ? 'fa-spinner fa-spin' : 'fa-check-circle'"></i>
        {{ discoveryResult.countsAvailable ? 'Refresh Counts' : 'Verify Record Counts' }}
    </button>
    <span ng-if="verifyProgress && verifyProgress.status === 'running'" style="margin-left: 10px;">
        <div class="progress" style="display: inline-block; width: 200px; height: 20px; margin-bottom: 0; vertical-align: middle;">
            <div class="progress-bar progress-bar-striped active"
                 style="width: {{ (verifyProgress.checked / verifyProgress.total * 100) }}%;">
                {{ verifyProgress.checked }} / {{ verifyProgress.total }}
            </div>
        </div>
        <small class="text-muted" style="margin-left: 5px;">
            {{ verifyProgress.withRecords }} with records
        </small>
    </span>
</div>
```

**Step 2: Add Records column to new sets table**

Update the `<thead>` in the new sets panel (line ~86-93):

```html
<thead>
    <tr>
        <th style="width: 30px;"></th>
        <th>Spec</th>
        <th>Title</th>
        <th style="width: 80px;">Records</th>
        <th>Mapping</th>
        <th style="width: 40px;"></th>
    </tr>
</thead>
```

Add a records `<td>` in the `<tbody>` row, after the title td (around line ~109):

```html
<td class="text-right">
    <span ng-if="set.recordCount !== null && set.recordCount !== undefined">
        {{ set.recordCount | number }}
    </span>
    <span ng-if="set.recordCount === null || set.recordCount === undefined" class="text-muted">
        &mdash;
    </span>
</td>
```

**Step 3: Add Empty Sets collapsible panel**

Add after the Existing Datasets panel (after line ~157), before the Ignored Sets panel:

```html
<!-- Empty Sets -->
<div class="panel panel-default" ng-if="discoveryResult.emptySets.length > 0">
    <div class="panel-heading" ng-click="showEmpty = !showEmpty" style="cursor: pointer;">
        <span class="badge pull-right">{{ discoveryResult.emptySets.length }}</span>
        <i class="fa" ng-class="showEmpty ? 'fa-chevron-down' : 'fa-chevron-right'"></i>
        Empty Sets <small class="text-muted">(0 records)</small>
    </div>
    <table class="table table-condensed" style="margin-bottom: 0;" ng-if="showEmpty">
        <tbody>
            <tr ng-repeat="set in discoveryResult.emptySets">
                <td>{{ set.normalizedSpec }}</td>
                <td>{{ set.title || set.setName }}</td>
                <td>
                    <button class="btn btn-xs btn-default" ng-click="ignoreSet(set)">
                        <i class="fa fa-ban"></i> Ignore
                    </button>
                </td>
            </tr>
        </tbody>
    </table>
</div>
```

**Step 4: Commit**

```bash
git add public/templates/discovery.html
git commit -m "feat(discovery): add records column, empty sets panel, and verification UI"
```

---

### Task 11: Frontend — Add sidebar badge

**Files:**
- Modify: `app/views/index.scala.html`
- Modify: `app/assets/javascripts/discovery/discovery-controllers.js`

**Step 1: Update sidebar nav item**

In `app/views/index.scala.html`, replace the discovery sidebar item (line ~70-74):

```html
<li class="sidebar-list" ng-if="enableDatasetDiscovery">
    <a href id="nav-discovery" data-ng-click="sidebarNav('discovery')">
        Dataset Discovery<span class="menu-icon fa fa-search-plus"></span>
        <span ng-if="discoveryNewCount > 0"
              class="badge"
              style="background-color: #d9534f; color: white; margin-left: 5px; font-size: 10px;">
            {{ discoveryNewCount }}
        </span>
    </a>
</li>
```

**Step 2: Load badge count from sources on init**

In `discovery-controllers.js`, update the `loadSources` function:

```javascript
function loadSources() {
    discoveryService.listSources().then(function(sources) {
        $scope.sources = sources;
        // Calculate total new sets across all sources for sidebar badge
        var totalNew = 0;
        sources.forEach(function(s) {
            if (s.newSetCount && s.enabled) {
                totalNew += s.newSetCount;
            }
        });
        $rootScope.discoveryNewCount = totalNew;
    });
}
```

**Step 3: Commit**

```bash
git add app/views/index.scala.html app/assets/javascripts/discovery/discovery-controllers.js
git commit -m "feat(discovery): add sidebar badge showing new set count"
```

---

### Task 12: Fix SetCountVerifier delay mechanism

**Files:**
- Modify: `app/discovery/SetCountVerifier.scala`

The initial implementation in Task 3 creates a new ActorSystem per delay, which is wrong. Fix to use a simple `Thread.sleep` in the sequential fold, or inject the existing ActorSystem.

**Step 1: Fix the delay to use Thread.sleep in a blocking context**

Replace the delay mechanism in the `verify` method. Since verification is already fire-and-forget and runs on its own Future chain, use `akka.pattern.after` with the injected ActorSystem scheduler:

Update the class to inject ActorSystem:

```scala
@Singleton
class SetCountVerifier @Inject()(
  wsClient: WSClient,
  actorSystem: akka.actor.ActorSystem
)(implicit ec: ExecutionContext) {
```

Replace the delay Future inside the fold:

```scala
val delayFuture = if (index > 0) {
  akka.pattern.after(delayMs.milliseconds, actorSystem.scheduler)(Future.successful(()))
} else {
  Future.successful(())
}
```

**Step 2: Run compile**

Run: `make compile`
Expected: Success

**Step 3: Commit**

```bash
git add app/discovery/SetCountVerifier.scala
git commit -m "fix(discovery): use injected ActorSystem for verification delay"
```

---

### Task 13: End-to-end manual test

**Step 1: Run the application**

Run: `sbt run`

**Step 2: Test the flow**

1. Navigate to `http://localhost:9000`, go to Dataset Discovery
2. Select the Memorix Maior ENB source
3. Click "Discover Sets" — should show sets as before, with "Verify Record Counts" button
4. Click "Verify Record Counts" — should show progress bar
5. Wait for completion — empty sets should move to "Empty Sets" panel, new sets should show record counts
6. Click "Discover Sets" again — cached counts should load immediately
7. Check sidebar badge shows correct count

**Step 3: Final commit if any adjustments needed**

```bash
git add -A
git commit -m "fix(discovery): adjustments from end-to-end testing"
```
