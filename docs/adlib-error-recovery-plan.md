# AdLib Harvester Error Recovery Implementation Plan

## Overview

This document outlines the implementation plan for adding error recovery and resilience capabilities to the Narthex AdLib harvester, inspired by the Go implementation in hub3-review2/ikuzo.

## Problem Statement

The current Narthex AdLib harvester stops completely when encountering errors on a harvest page. Due to unstable endpoints, we need:
1. Ability to continue harvesting when individual pages fail
2. Automatic retry at the record level for failed pages
3. Storage of failed records for user review and manual correction
4. Detailed error reporting and metrics

## Solution Architecture

### Design Principles

1. **Sequential Processing**: Maintain existing actor-based message-passing pattern for simplicity and debugging
2. **Separate Error Storage**: Failed records stored in `.errors.zip` with accompanying `.errors.txt` log
3. **User Configurable**: Per-dataset error handling strategy via UI
4. **Detailed Metrics**: Separate progress counters for error-related activities
5. **Backward Compatible**: Existing harvests work unchanged if error recovery disabled

### Comparison with Go Implementation

| Feature | Go Implementation (hub3) | Scala Implementation (Narthex) |
|---------|-------------------------|-------------------------------|
| **Concurrency** | Worker pool (4 workers) | Sequential actor messages |
| **Error Storage** | `.errors.txt` file only | `.errors.zip` + `.errors.txt` |
| **Page Breakdown** | `createSingleRecordPages()` | `breakPageIntoRecords()` |
| **Error Tracking** | `HarvestConfig.HarvestErrors` map | `harvestErrors` map + `errorRecords` list |
| **Progress Updates** | Every 500 records | Configurable via ProgressReporter |
| **Retry Strategy** | Immediate retry as single records | Same approach |
| **Threshold Control** | Not implemented | User-configurable threshold |

## Implementation Phases

### Phase 1: Data Model Extension

**File**: `app/triplestore/GraphProperties.scala`

Add new RDF properties for error handling configuration:

```scala
// Around line 113, after harvestFullCount
val harvestContinueOnError = NXProp("harvestContinueOnError", booleanProp)
val harvestErrorThreshold = NXProp("harvestErrorThreshold", intProp)
val harvestErrorCount = NXProp("harvestErrorCount", intProp)
val harvestErrorRecoveryAttempts = NXProp("harvestErrorRecoveryAttempts", intProp)
```

**Purpose**: Store per-dataset configuration for error handling behavior.

### Phase 2: Harvester State Extension

**File**: `app/harvest/Harvester.scala`

Add state variables to the `Harvester` class:

```scala
class Harvester(...) extends Actor with Harvesting with ActorLogging {

  // Existing state
  var tempFileOpt: Option[File] = None
  var zipOutputOpt: Option[ZipOutputStream] = None
  var pageCount = 0
  var progressOpt: Option[ProgressReporter] = None

  // NEW: Error recovery state
  var harvestErrors: Map[String, String] = Map.empty
  var errorRecords: List[(String, String, String)] = List.empty // (recordId, xml, error)
  var errorPagesSubmitted: Int = 0
  var errorPagesProcessed: Int = 0
  var continueOnError: Boolean = false
  var errorThresholdOpt: Option[Int] = None
  var recordsProcessed: Int = 0
  var tempErrorZipOpt: Option[File] = None
  var errorZipOutputOpt: Option[ZipOutputStream] = None
```

### Phase 3: Error Recovery Methods

**File**: `app/harvest/Harvester.scala`

#### 3.1 Break Page Into Single Records

```scala
/**
 * Breaks a failed page URL into individual record URLs for retry.
 * Mimics Go implementation: harvestError.createSingleRecordPages()
 */
def breakPageIntoRecords(
  pageUrl: String,
  limit: Int,
  offset: Int
): List[String] = {
  logger.info(s"Breaking failed page into $limit single-record requests: $pageUrl")

  (0 until limit).map { i =>
    val recordOffset = offset + i
    // Return a modified URL with limit=1 and adjusted startFrom
    pageUrl.replaceAll(
      "limit=\\d+", s"limit=1"
    ).replaceAll(
      "startFrom=\\d+", s"startFrom=$recordOffset"
    )
  }.toList
}
```

#### 3.2 Check Error Threshold

```scala
/**
 * Checks if error rate has exceeded configured threshold.
 * Returns true if harvesting should continue, false if should stop.
 */
def checkErrorThreshold(): Boolean = {
  errorThresholdOpt match {
    case None => true // No threshold configured, always continue
    case Some(threshold) if threshold == 0 => true // 0 means unlimited errors
    case Some(threshold) =>
      val totalRecords = recordsProcessed + errorRecords.size
      if (totalRecords == 0) return true

      val errorPercentage = (errorRecords.size.toDouble / totalRecords.toDouble) * 100
      val shouldContinue = errorPercentage < threshold

      if (!shouldContinue) {
        logger.error(
          s"Error threshold exceeded: ${errorPercentage}% errors " +
          s"(${errorRecords.size} / $totalRecords) exceeds limit of $threshold%"
        )
      }

      shouldContinue
  }
}
```

#### 3.3 Create Error Files

```scala
/**
 * Creates error output files: .errors.zip and .errors.txt
 * Mimics Go implementation's error file creation
 */
def createErrorFiles(): (Option[File], Option[File]) = {
  if (errorRecords.isEmpty && harvestErrors.isEmpty) {
    return (None, None)
  }

  logger.info(s"Creating error files: ${errorRecords.size} failed records, ${harvestErrors.size} page errors")

  // Create .errors.zip with failed record XML
  val errorsZipOpt = if (errorRecords.nonEmpty) {
    try {
      val errorsZipFile = File.createTempFile("narthex-harvest-errors", ".zip")
      val fos = new FileOutputStream(errorsZipFile)
      val zipOut = new ZipOutputStream(fos)

      errorRecords.zipWithIndex.foreach { case ((recordId, xml, error), idx) =>
        zipOut.putNextEntry(new ZipEntry(s"error_record_${recordId}_$idx.xml"))
        zipOut.write(xml.getBytes("UTF-8"))
        zipOut.closeEntry()
      }

      zipOut.close()
      fos.close()
      Some(errorsZipFile)
    } catch {
      case NonFatal(e) =>
        logger.error(s"Failed to create errors ZIP file: ${e.getMessage}", e)
        None
    }
  } else {
    None
  }

  // Create .errors.txt with error log
  val errorLogOpt = if (harvestErrors.nonEmpty || errorRecords.nonEmpty) {
    try {
      val errorLogFile = File.createTempFile("narthex-harvest-errors", ".txt")
      val writer = new BufferedWriter(new OutputStreamWriter(
        new FileOutputStream(errorLogFile), "UTF-8"
      ))

      writer.write(s"AdLib Harvest Error Report\n")
      writer.write(s"===========================\n\n")
      writer.write(s"Total Pages with Errors: ${harvestErrors.size}\n")
      writer.write(s"Total Failed Records: ${errorRecords.size}\n")
      writer.write(s"Error Recovery Attempts: $errorPagesSubmitted\n")
      writer.write(s"Error Recoveries Processed: $errorPagesProcessed\n\n")

      if (harvestErrors.nonEmpty) {
        writer.write(s"Page Errors:\n")
        writer.write(s"------------\n")
        harvestErrors.foreach { case (pageUrl, errorMsg) =>
          writer.write(s"URL: $pageUrl\n")
          writer.write(s"Error: $errorMsg\n\n")
        }
      }

      if (errorRecords.nonEmpty) {
        writer.write(s"\nFailed Records:\n")
        writer.write(s"---------------\n")
        errorRecords.foreach { case (recordId, _, errorMsg) =>
          writer.write(s"Record ID: $recordId\n")
          writer.write(s"Error: $errorMsg\n\n")
        }
      }

      writer.close()
      Some(errorLogFile)
    } catch {
      case NonFatal(e) =>
        logger.error(s"Failed to create error log file: ${e.getMessage}", e)
        None
    }
  } else {
    None
  }

  (errorsZipOpt, errorLogOpt)
}
```

### Phase 4: Message Handling Updates

**File**: `app/harvest/Harvester.scala`

#### 4.1 New Message Type

```scala
object Harvester {
  // Existing messages...

  // NEW: Single record harvest for error recovery
  case class AdLibSingleRecordHarvest(
    recordOffset: Int,
    url: String,
    database: String,
    search: String,
    strategy: HarvestStrategy,
    originalPageUrl: String
  )
}
```

#### 4.2 Update AdLibHarvestPage Handler

```scala
case AdLibHarvestPage(records, url, database, search, strategy, diagnostic) => actorWork(context) {
  Try(addPage(records)) match {
    case Success(pageNumber) =>
      recordsProcessed += diagnostic.pageItems

      // Existing progress reporting
      strategy match {
        case ModifiedAfter(_, _) =>
          progressOpt.get.sendPage(pageNumber)
        case _ =>
          progressOpt.get.sendPercent(diagnostic.percentComplete)
      }

      logger.info(s"Harvest Page: $pageNumber - $url $database to $datasetContext: $diagnostic")

      if (diagnostic.totalItems == 0) {
        finish(strategy, Some("noRecordsMatch"))
      }
      else if (diagnostic.isLast) {
        finish(strategy, None)
      }
      else {
        val futurePage = fetchAdLibPage(timeout, wsApi, strategy, url, database, search, Some(diagnostic))
        handleFailure(futurePage, strategy, "adlib harvest page")
        futurePage pipeTo self
      }

    case Failure(e) if continueOnError =>
      // NEW: Error recovery path
      logger.warn(s"Failed to process harvest page, breaking into individual records: ${e.getMessage}")
      harvestErrors += (url -> e.toString)

      val recordUrls = breakPageIntoRecords(url, diagnostic.pageItems, diagnostic.current)
      recordUrls.zipWithIndex.foreach { case (recordUrl, idx) =>
        self ! AdLibSingleRecordHarvest(
          recordOffset = diagnostic.current + idx,
          url = url,
          database = database,
          search = search,
          strategy = strategy,
          originalPageUrl = url
        )
      }

      // Continue with next page
      if (!diagnostic.isLast) {
        val futurePage = fetchAdLibPage(timeout, wsApi, strategy, url, database, search, Some(diagnostic))
        handleFailure(futurePage, strategy, "adlib harvest page")
        futurePage pipeTo self
      } else {
        // Wait for all error recovery to complete before finishing
        // This is handled by tracking errorPagesSubmitted vs errorPagesProcessed
      }

    case Failure(e) =>
      // Error recovery disabled, fail immediately
      logger.error(s"Failed to process harvest page: ${e.getMessage}", e)
      finish(strategy, Some(e.toString))
  }
}
```

#### 4.3 Add Single Record Handler

```scala
case AdLibSingleRecordHarvest(recordOffset, url, database, search, strategy, originalPageUrl) => actorWork(context) {
  errorPagesSubmitted += 1
  logger.debug(s"Attempting single-record harvest at offset $recordOffset")

  val futurePage = fetchAdLibPage(
    timeout, wsApi, strategy, url, database, search,
    limit = 1,
    startFrom = Some(recordOffset)
  )

  futurePage.onComplete {
    case Success(AdLibHarvestPage(records, _, _, _, _, _)) =>
      Try(addPage(records)) match {
        case Success(_) =>
          logger.debug(s"Successfully recovered record at offset $recordOffset")
          recordsProcessed += 1
        case Failure(e) =>
          logger.warn(s"Failed to recover record at offset $recordOffset: ${e.getMessage}")
          errorRecords :+= (recordOffset.toString, records, e.toString)
      }
      errorPagesProcessed += 1

      // Check if we're done with all recovery attempts
      checkIfHarvestComplete(strategy)

    case Success(HarvestError(error, _)) =>
      logger.warn(s"Error retrieving single record at offset $recordOffset: $error")
      errorRecords :+= (recordOffset.toString, "", error)
      errorPagesProcessed += 1
      checkIfHarvestComplete(strategy)

    case Failure(e) =>
      logger.warn(s"Failed single-record harvest at offset $recordOffset: ${e.getMessage}")
      errorRecords :+= (recordOffset.toString, "", e.toString)
      errorPagesProcessed += 1
      checkIfHarvestComplete(strategy)
  }

  // Check error threshold
  if (!checkErrorThreshold()) {
    finish(strategy, Some("Error threshold exceeded"))
  }
}

/**
 * Check if harvest is complete (all pages and error recoveries done)
 */
def checkIfHarvestComplete(strategy: HarvestStrategy): Unit = {
  // Logic to determine if we're done with main harvest and all retries
  // This is tricky with the async nature - may need additional state tracking
}
```

### Phase 5: Update fetchAdLibPage Signature

**File**: `app/harvest/Harvesting.scala`

```scala
def fetchAdLibPage(
  timeOut: Long,
  wsApi: WSClient,
  strategy: HarvestStrategy,
  url: String,
  database: String,
  search: String,
  diagnosticOption: Option[AdLibDiagnostic] = None,
  limit: Int = 50,  // NEW: Allow custom limit for single-record requests
  startFrom: Option[Int] = None  // NEW: Allow explicit startFrom override
)(implicit harvestExecutionContext: ExecutionContext): Future[AnyRef] = {

  val startFromValue = startFrom.getOrElse(
    diagnosticOption.map(d => d.current + d.pageItems).getOrElse(1)
  )

  val cleanUrl = url.stripSuffix("?")
  val requestUrl = wsApi.url(cleanUrl).withRequestTimeout(timeOut.milliseconds)

  val searchModified = strategy match {
    case ModifiedAfter(mod, _) =>
      s"modification greater '${timeToLocalString(mod)}'"
    case _ =>
      val cleanSearch = if (search.isEmpty) "all" else search
      if (cleanSearch contains "%20") cleanSearch.replace("%20", "") else cleanSearch
  }

  val request = requestUrl.withQueryString(
    "database" -> database,
    "search" -> searchModified,
    "xmltype" -> "grouped",
    "limit" -> limit.toString,  // Use configurable limit
    "startFrom" -> startFromValue.toString
  )

  // ... rest of existing implementation
}
```

### Phase 6: Update finish() Method

**File**: `app/harvest/Harvester.scala`

```scala
def finish(strategy: HarvestStrategy, errorOpt: Option[String]) = {
  // Close main ZIP
  zipOutputOpt.foreach { zipOutput =>
    try {
      zipOutput.close()
    } catch {
      case NonFatal(e) =>
        log.warning(s"Error closing ZipOutputStream in finish: ${e.getMessage}")
    }
  }
  zipOutputOpt = None

  // Create error files if any errors occurred
  val (errorsZipOpt, errorLogOpt) = createErrorFiles()

  // Update dataset properties with error metrics
  datasetContext.dsInfo.setSingularLiteralProps(
    harvestErrorCount -> errorRecords.size,
    harvestErrorRecoveryAttempts -> errorPagesSubmitted
  )

  log.info(
    s"Finished $strategy harvest for dataset $datasetContext: " +
    s"errors=${errorRecords.size}, recovery_attempts=$errorPagesSubmitted, " +
    s"records_processed=$recordsProcessed"
  )

  errorOpt match {
    case Some("noRecordsMatch") =>
      log.info(s"Finished incremental harvest with noRecordsMatch")
      context.parent ! HarvestComplete(strategy, None, true)

    case None | Some("noRecordsMatchRecoverable") =>
      strategy match {
        case Sample =>
          context.parent ! HarvestComplete(strategy, None)
        case _ =>
          datasetContext.sourceRepoOpt match {
            case Some(sourceRepo) =>
              Future {
                val acceptZipReporter = ProgressReporter(COLLECTING, context.parent)
                val fileOption = sourceRepo.acceptFile(tempFileOpt.get, acceptZipReporter)

                // Also save error files if they exist
                errorsZipOpt.foreach { errZip =>
                  sourceRepo.acceptErrorFile(errZip, "errors.zip")
                }
                errorLogOpt.foreach { errLog =>
                  sourceRepo.acceptErrorFile(errLog, "errors.txt")
                }

                log.info(s"Zip file accepted: $fileOption")
                context.parent ! HarvestComplete(strategy, fileOption)
              } onComplete {
                case Success(v) => v
                case Failure(e) =>
                  log.info(s"error on accepting sip-file: ${e}")
                  context.parent ! WorkFailure(e.getMessage)
              }
            case None =>
              context.parent ! WorkFailure(s"No source repo for $datasetContext")
          }
      }

    case Some(message) =>
      context.parent ! WorkFailure(message)
  }
}
```

### Phase 7: SourceRepo Error File Handling

**File**: `app/dataset/SourceRepo.scala` (if needed)

Add method to accept error files:

```scala
def acceptErrorFile(file: File, suffix: String): Option[File] = {
  // Store error file alongside harvest data
  val errorFile = new File(datasetDir, s"harvest_$suffix")
  FileUtils.copyFile(file, errorFile)
  Some(errorFile)
}
```

### Phase 8: Progress Reporting Updates

**File**: `app/dataset/DatasetActor.scala`

Update `Active` case class:

```scala
case class Active(
  spec: String,
  childOpt: Option[ActorRef],
  progressState: ProgressState,
  progressType: ProgressType = TYPE_IDLE,
  count: Int = 0,
  errorCount: Int = 0,  // NEW
  errorRecoveryAttempts: Int = 0,  // NEW
  interrupt: Boolean = false
) extends DatasetActorData

implicit val activeWrites: Writes[Active] = new Writes[Active] {
  def writes(active: Active) = Json.obj(
    "datasetSpec" -> active.spec,
    "progressState" -> active.progressState.toString,
    "progressType" -> active.progressType.toString,
    "count" -> active.count,
    "errorCount" -> active.errorCount,  // NEW
    "errorRecoveryAttempts" -> active.errorRecoveryAttempts,  // NEW
    "interrupt" -> active.interrupt
  )
}
```

### Phase 9: UI Configuration

**File**: `public/templates/dataset-list.html`

Add error handling configuration section (around line 300, in the AdLib harvest section):

```html
<!-- Error Handling Configuration (AdLib-specific) -->
<div ng-show="dataset.edit.harvestType == 'adlib'" class="form-group">
  <label class="control-label">
    <strong>Error Handling</strong>
  </label>

  <div class="checkbox">
    <label>
      <input type="checkbox"
             ng-model="dataset.edit.harvestContinueOnError"
             ng-true-value="true"
             ng-false-value="false">
      Continue harvesting when encountering errors
      <span class="help-block">
        If enabled, harvest will continue when pages fail and retry individual records.
        Failed records will be stored separately for manual review.
      </span>
    </label>
  </div>

  <div ng-show="dataset.edit.harvestContinueOnError" class="form-group">
    <label for="error_threshold">
      Error Threshold (%)
    </label>
    <input type="number"
           id="error_threshold"
           class="form-control"
           min="0"
           max="100"
           ng-model="dataset.edit.harvestErrorThreshold"
           placeholder="10">
    <span class="help-block">
      Stop harvesting if error rate exceeds this percentage (0 = unlimited errors)
    </span>
  </div>
</div>
```

**File**: `app/assets/javascripts/datasetList/dataset-list-controllers.js`

Update to handle error configuration:

```javascript
// In the save/update dataset function, include error config fields
dataset.harvestContinueOnError = $scope.dataset.edit.harvestContinueOnError || false;
dataset.harvestErrorThreshold = $scope.dataset.edit.harvestErrorThreshold || 10;
```

### Phase 10: Load Error Configuration

**File**: `app/dataset/DsInfo.scala`

Add methods to get error configuration:

```scala
def getContinueOnError: Boolean =
  getOptionalProperty(harvestContinueOnError).getOrElse(false)

def getErrorThreshold: Int =
  getOptionalProperty(harvestErrorThreshold).map(_.toInt).getOrElse(10)
```

**File**: `app/harvest/Harvester.scala`

Initialize error settings from DsInfo:

```scala
// In constructor or receive method initialization
continueOnError = datasetContext.dsInfo.getContinueOnError
errorThresholdOpt = Some(datasetContext.dsInfo.getErrorThreshold)
```

## Testing Strategy

### Unit Tests

1. **breakPageIntoRecords()** - Test URL generation
   - Verify correct limit=1 replacement
   - Verify correct startFrom calculation
   - Test with various URL formats

2. **checkErrorThreshold()** - Test threshold logic
   - No threshold (None)
   - Zero threshold (unlimited)
   - Various percentage scenarios

3. **createErrorFiles()** - Test file generation
   - Empty error lists
   - Single error
   - Multiple errors
   - UTF-8 encoding

### Integration Tests

1. Mock AdLib endpoint that returns errors on specific pages
2. Verify error recovery kicks in
3. Verify error files are created
4. Verify harvest continues after errors
5. Verify threshold stops harvest

### Manual Testing Checklist

- [ ] Enable error recovery in UI
- [ ] Configure error threshold
- [ ] Trigger harvest with unstable endpoint
- [ ] Verify progress shows error counts
- [ ] Verify .errors.zip is downloadable
- [ ] Verify .errors.txt contains correct information
- [ ] Verify successful records are in main harvest
- [ ] Test with threshold = 0 (unlimited)
- [ ] Test with threshold = 100 (stop on first error)

## Migration and Rollout

### Backward Compatibility

- Default `harvestContinueOnError = false` maintains current behavior
- Existing harvests unaffected
- No database migrations required (properties stored in RDF)

### Deployment Steps

1. Deploy code changes
2. Test with single dataset
3. Enable for problematic datasets
4. Monitor error logs
5. Gradually enable for more datasets

## Future Enhancements

1. **Parallel Error Recovery**: Process error retries in parallel (like Go implementation)
2. **Configurable Retry Count**: Allow N retries before giving up
3. **Error Dashboard**: UI to view and manage failed records
4. **Auto-Reprocess**: Button to reprocess failed records after manual fixes
5. **Error Notifications**: Email alerts when error threshold exceeded
6. **Statistics**: Detailed error rate statistics over time

## References

- Go Implementation: `/home/kiivihal/code/golang/hub3-review2/ikuzo/service/x/adlib/harvest.go`
- Current Scala Implementation: `/home/kiivihal/code/scala/narthex/app/harvest/Harvester.scala`
- AdLib API Documentation: (link if available)

## Implementation Timeline

| Phase | Task | Estimated Time |
|-------|------|----------------|
| 1 | Data Model | 2 hours |
| 2 | State Variables | 1 hour |
| 3 | Core Methods | 4 hours |
| 4 | Message Handling | 6 hours |
| 5 | fetchAdLibPage Update | 2 hours |
| 6 | finish() Update | 2 hours |
| 7 | SourceRepo | 1 hour |
| 8 | Progress Reporting | 2 hours |
| 9 | UI Configuration | 3 hours |
| 10 | Load Configuration | 1 hour |
| 11 | Testing | 8 hours |

**Total: ~32 hours (4 days)**
