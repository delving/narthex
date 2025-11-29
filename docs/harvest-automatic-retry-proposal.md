# Automatic Harvest Retry System - Implementation Proposal

## Overview

This document describes the proposed implementation for automatic transparent retry of failed harvests in Narthex. When a harvest fails due to temporary server issues, the system will automatically retry at configurable intervals without requiring manual error dismissal, showing the user the retry status and attempt count.

## Problem Statement

Currently, when a harvest fails (e.g., due to temporary AdLib or OAI-PMH server issues), the system:
1. Stops the harvest and sets an error state
2. Displays an error message in the UI
3. Requires a human to manually dismiss the error before operations can continue
4. The user must then manually restart the harvest

This is problematic because:
- Temporary server issues often resolve themselves within minutes/hours
- Manual intervention is required even for transient failures
- Users may not notice the error promptly, causing delays
- Repeated manual restarts are tedious and error-prone

## Proposed Solution

Implement automatic transparent retry with the following characteristics:

### Key Features

| Feature | Description |
|---------|-------------|
| **All harvest types** | Applies to OAI-PMH, AdLib, and Download harvests |
| **Unlimited retries** | Keeps retrying until manual intervention (no max retry limit) |
| **Configurable interval** | System-wide retry interval set in `application.conf` (default: 60 minutes) |
| **Transparent to user** | No blocking error message during retry attempts |
| **Visible status** | UI shows "Harvest failed, next retry in X minutes (attempt #N)" |
| **Manual controls** | User can stop retrying, trigger immediate retry, or clear error |

### User Experience Flow

```
Normal Harvest
     |
     v
  [Harvest Fails]
     |
     v
  [Enter Retry Mode] -----> UI shows: "Harvest failed, retrying in 60 min (attempt #1)"
     |                                  [Retry Now] [Stop Retrying]
     |
     | (60 minutes pass)
     v
  [Automatic Retry #2] ---> UI shows: "Harvest failed, retrying in 60 min (attempt #2)"
     |
     | (success)
     v
  [Harvest Complete] -----> Normal state, retry count cleared

     OR

     | (user clicks "Stop Retrying")
     v
  [Show Error] -----------> Blocking error requiring manual dismissal
```

---

## Technical Design

### Architecture Overview

The retry system integrates with existing Narthex components:

```
┌─────────────────────────────────────────────────────────────────┐
│                        PeriodicHarvest Actor                     │
│  (runs every 1 minute)                                          │
│                                                                 │
│  1. Check scheduled harvests (existing)                         │
│  2. Check datasets in retry mode (NEW)                          │
│     - Query for datasets with harvestInRetry=true               │
│     - Check if retryInterval has passed                         │
│     - Acquire semaphore and trigger retry                       │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                        DatasetActor                              │
│                                                                 │
│  States:                                                        │
│  - Idle/Dormant (existing)                                      │
│  - Idle/InError (existing)                                      │
│  - Idle/InRetry (NEW) - holds retry count, error message        │
│                                                                 │
│  Transitions:                                                   │
│  - WorkFailure + isHarvest → InRetry (NEW)                     │
│  - HarvestComplete + InRetry → Dormant (clear retry)           │
│  - "stop retrying" command → InError                           │
│  - "retry now" command → StartHarvest                          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                        DsInfo (Dataset Info)                     │
│                                                                 │
│  New Properties:                                                │
│  - harvestInRetry: Boolean                                      │
│  - harvestRetryCount: Int                                       │
│  - harvestLastRetryTime: DateTime                               │
│  - harvestRetryMessage: String                                  │
│                                                                 │
│  New Methods:                                                   │
│  - setInRetry(message, count)                                   │
│  - incrementRetryCount(): Int                                   │
│  - clearRetryState()                                            │
│  - isInRetry(): Boolean                                         │
│  - getNextRetryTime(): DateTime                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Implementation Plan

### Phase 1: Add Configuration and Properties

#### 1.1 Configuration Setting

**File**: `conf/application.conf`

Add system-wide retry interval setting:

```hocon
# Harvest retry configuration
narthex.harvest {
  # Interval between retry attempts in minutes (default: 60 = 1 hour)
  retryIntervalMinutes = 60
}
```

#### 1.2 Load Configuration

**File**: `app/organization/AppConfig.scala`

Add configuration loading:

```scala
// Add to AppConfig class
val harvestRetryIntervalMinutes: Int =
  configuration.getOptional[Int]("narthex.harvest.retryIntervalMinutes").getOrElse(60)
```

#### 1.3 Add RDF Properties

**File**: `app/triplestore/GraphProperties.scala`

Add new properties for retry tracking:

```scala
// Harvest retry properties
val harvestInRetry = NXProp("harvestInRetry", booleanProp)
val harvestRetryCount = NXProp("harvestRetryCount", intProp)
val harvestLastRetryTime = NXProp("harvestLastRetryTime", timeProp)
val harvestRetryMessage = NXProp("harvestRetryMessage")
```

---

### Phase 2: Update DsInfo with Retry Methods

**File**: `app/dataset/DsInfo.scala`

Add helper methods for retry state management:

```scala
/**
 * Set the dataset into retry mode after a harvest failure.
 * This clears any existing error state and initializes retry tracking.
 */
def setInRetry(message: String, retryCount: Int = 0): Unit = {
  // Clear existing error message (don't block UI)
  removeLiteralProp(datasetErrorMessage)

  // Set retry state
  setSingularLiteralProps(
    harvestInRetry -> "true",
    harvestRetryCount -> retryCount.toString,
    harvestLastRetryTime -> now,
    harvestRetryMessage -> message
  )
}

/**
 * Increment the retry count and update last retry time.
 * Called before each retry attempt.
 * @return The new retry count
 */
def incrementRetryCount(): Int = {
  val currentCount = getLiteralProp(harvestRetryCount).map(_.toInt).getOrElse(0)
  val newCount = currentCount + 1
  setSingularLiteralProps(
    harvestRetryCount -> newCount.toString,
    harvestLastRetryTime -> now
  )
  newCount
}

/**
 * Clear all retry state. Called on successful harvest or manual stop.
 */
def clearRetryState(): Unit = {
  removeLiteralProp(harvestInRetry)
  removeLiteralProp(harvestRetryCount)
  removeLiteralProp(harvestLastRetryTime)
  removeLiteralProp(harvestRetryMessage)
}

/**
 * Check if dataset is currently in retry mode.
 */
def isInRetry: Boolean =
  getLiteralProp(harvestInRetry).exists(_ == "true")

/**
 * Get current retry count.
 */
def getRetryCount: Int =
  getLiteralProp(harvestRetryCount).map(_.toInt).getOrElse(0)

/**
 * Get timestamp of last retry attempt.
 */
def getLastRetryTime: Option[DateTime] =
  getTimeProp(harvestLastRetryTime)

/**
 * Get the error message that triggered retry mode.
 */
def getRetryMessage: Option[String] =
  getLiteralProp(harvestRetryMessage)

/**
 * Calculate when the next retry should occur.
 * @param intervalMinutes The configured retry interval
 */
def getNextRetryTime(intervalMinutes: Int): DateTime = {
  getLastRetryTime match {
    case Some(lastRetry) => lastRetry.plusMinutes(intervalMinutes)
    case None => new DateTime() // Retry immediately if no last time
  }
}

/**
 * Check if enough time has passed for next retry.
 * @param intervalMinutes The configured retry interval
 */
def isTimeForRetry(intervalMinutes: Int): Boolean = {
  getNextRetryTime(intervalMinutes).isBeforeNow
}
```

---

### Phase 3: Update DatasetActor State Machine

**File**: `app/dataset/DatasetActor.scala`

#### 3.1 Add New State Data Type

```scala
// Add to existing state data types (around line 120)
case class InRetry(message: String, retryCount: Int) extends Data
```

#### 3.2 Helper Method to Detect Harvest Failures

```scala
// Add helper method
private def isHarvestFailure(active: Active): Boolean = {
  active.progressState == HARVESTING
}
```

#### 3.3 Modify WorkFailure Handler

Replace the existing `WorkFailure` handler (around line 753) to support retry:

```scala
case Event(WorkFailure(message, exceptionOpt), active: Active) =>
  log.warning(s"Work failure [$message] while in [$active]")

  // Check if this is a harvest failure (candidate for retry)
  if (isHarvestFailure(active)) {
    log.info(s"Harvest failure detected, entering retry mode for ${dsInfo.spec}")

    // Set retry state in dataset properties
    dsInfo.setInRetry(message, 0)

    // Log the error but don't send email yet (will send after max retries or manual stop)
    exceptionOpt match {
      case Some(exception) => log.error(exception, message)
      case None            => log.error(message)
    }

    // Release semaphore so PeriodicHarvest can re-acquire for retry
    active.childOpt.foreach(_ ! PoisonPill)
    orgContext.semaphore.release(dsInfo.spec)
    orgContext.saveSemaphore.release(dsInfo.spec)

    // Transition to retry state (not error state)
    goto(Idle) using InRetry(message, 0)

  } else {
    // Non-harvest failure - use existing error handling
    dsInfo.setError(s"While $stateName, failure: $message")
    exceptionOpt match {
      case Some(exception) => log.error(exception, message)
      case None            => log.error(message)
    }
    mailService.sendProcessingErrorMessage(dsInfo.spec, message, exceptionOpt)
    active.childOpt.foreach(_ ! PoisonPill)
    goto(Idle) using InError(message)
  }
```

#### 3.4 Handle Commands in Retry State

Add new event handlers for the `InRetry` state:

```scala
// Handle commands while in retry state
case Event(Command(commandName), InRetry(message, retryCount)) =>
  log.info(s"In retry mode (attempt #$retryCount). Command: $commandName")

  commandName match {
    case "stop retrying" =>
      log.info(s"User stopped retrying after $retryCount attempts")
      dsInfo.clearRetryState()
      dsInfo.setError(s"Harvest stopped after $retryCount retry attempts: $message")
      mailService.sendProcessingErrorMessage(dsInfo.spec, message, None)
      goto(Idle) using InError(message)

    case "retry now" =>
      log.info(s"User triggered immediate retry (attempt #${retryCount + 1})")
      val newCount = dsInfo.incrementRetryCount()
      if (orgContext.semaphore.tryAcquire(dsInfo.spec)) {
        self ! startHarvestMessage()
        goto(Idle) using InRetry(message, newCount)
      } else {
        log.warning("Could not acquire semaphore for immediate retry")
        stay()
      }

    case "clear error" =>
      // User wants to completely clear retry state
      log.info("User cleared retry state")
      dsInfo.clearRetryState()
      goto(Idle) using Dormant

    case _ =>
      log.info(s"Ignoring command '$commandName' while in retry mode")
      stay()
  }

// Handle successful harvest completion while in retry mode
case Event(HarvestComplete(strategy, fileOpt, noRecordsMatch), InRetry(message, retryCount)) =>
  log.info(s"Harvest succeeded after $retryCount retry attempts for ${dsInfo.spec}")
  dsInfo.clearRetryState()
  orgContext.semaphore.release(dsInfo.spec)

  // Continue with normal harvest completion flow
  if (noRecordsMatch) {
    goto(Idle) using Dormant
  } else {
    fileOpt match {
      case Some(file) =>
        dsInfo.setState(DsState.RAW)
        // ... rest of normal completion handling
      case None =>
        goto(Idle) using Dormant
    }
  }

// Handle another failure while already in retry mode
case Event(WorkFailure(newMessage, exceptionOpt), InRetry(oldMessage, retryCount)) =>
  log.warning(s"Retry attempt #$retryCount failed: $newMessage")

  // Update retry state with new message but keep count
  dsInfo.setInRetry(newMessage, retryCount)

  exceptionOpt match {
    case Some(exception) => log.error(exception, newMessage)
    case None            => log.error(newMessage)
  }

  // Stay in retry mode, PeriodicHarvest will trigger next attempt
  orgContext.semaphore.release(dsInfo.spec)
  stay() using InRetry(newMessage, retryCount)
```

#### 3.5 Handle StartHarvest While in Retry

```scala
// Allow StartHarvest while in retry mode (triggered by PeriodicHarvest or manual retry)
case Event(StartHarvest(strategy), InRetry(message, retryCount)) =>
  log.info(s"Starting retry harvest attempt #${retryCount + 1} for ${dsInfo.spec}")

  // Increment retry count
  val newCount = dsInfo.incrementRetryCount()

  // Start harvest (reuse existing harvest startup logic)
  def prop(p: NXProp) = dsInfo.getLiteralProp(p).getOrElse("")
  harvestTypeFromString(prop(harvestType)).map { harvestType =>
    val kickoff = createHarvestKickoff(harvestType, strategy, prop)
    val harvester = createChildActor(
      Harvester.props(datasetContext, orgContext.appConfig.harvestTimeOut,
                     orgContext.wsApi, harvestingExecutionContext),
      "harvester"
    )
    harvester ! kickoff
    goto(Harvesting) using Active(dsInfo.spec, Some(harvester), HARVESTING)
  } getOrElse {
    stay() using InRetry(message, newCount)
  }
```

---

### Phase 4: Extend PeriodicHarvest for Retry Checking

**File**: `app/harvest/PeriodicHarvest.scala`

#### 4.1 Add Retry Check to ScanForHarvests

Modify the `ScanForHarvests` handler to also check for datasets in retry mode:

```scala
case ScanForHarvests =>
  logger.info(s"PeriodicHarvest: Scanning for datasets")

  // Existing scheduled harvest check
  DsInfo.listDsInfoWithStateFilter(orgContext, harvestingAllowed.map(_.toString)).map { dsInfoList =>
    logger.info(s"PeriodicHarvest: Found ${dsInfoList.length} datasets in harvestable states")

    // Check scheduled harvests (existing code)
    dsInfoList.filter(_.hasPreviousTime()).foreach { info =>
      // ... existing scheduled harvest logic ...
    }

    // NEW: Check for datasets in retry mode
    checkRetryHarvests()
  }

/**
 * Check for datasets in retry mode and trigger retry if interval has passed.
 */
private def checkRetryHarvests(): Unit = {
  val retryIntervalMinutes = orgContext.appConfig.harvestRetryIntervalMinutes

  DsInfo.listDsInfoInRetry(orgContext).map { retryList =>
    logger.info(s"PeriodicHarvest: Found ${retryList.length} datasets in retry mode")

    retryList.filter(_.isTimeForRetry(retryIntervalMinutes)).foreach { info =>
      logger.info(s"PeriodicHarvest: Time for retry harvest of ${info.spec}")

      if (orgContext.semaphore.tryAcquire(info.spec)) {
        logger.info(s"PeriodicHarvest: Acquired semaphore, triggering retry for ${info.spec}")

        // Determine harvest strategy (use FromScratch for retries)
        val strategy = FromScratch

        // Send message to trigger harvest
        orgContext.orgActor ! info.createMessage(StartHarvest(strategy))
      } else {
        logger.info(s"PeriodicHarvest: Could not acquire semaphore for ${info.spec}, will retry later")
      }
    }
  }
}
```

#### 4.2 Add Query Method for Retry Datasets

**File**: `app/dataset/DsInfo.scala`

Add companion object method:

```scala
/**
 * List all datasets currently in retry mode.
 */
def listDsInfoInRetry(orgContext: OrgContext)(
    implicit ec: ExecutionContext,
    ts: TripleStore): Future[List[DsInfo]] = {

  ts.query(Sparql.selectDatasetsInRetryQ).map { list =>
    list.map { entry =>
      val spec = entry("spec").text
      new DsInfo(
        spec,
        orgContext.appConfig.nxUriPrefix,
        orgContext.appConfig.naveApiAuthToken,
        orgContext.appConfig.naveApiUrl,
        orgContext,
        orgContext.appConfig.mockBulkApi
      )
    }
  }
}
```

#### 4.3 Add SPARQL Query

**File**: `app/triplestore/Sparql.scala`

```scala
def selectDatasetsInRetryQ =
  s"""
    |PREFIX nx: <${NX_NAMESPACE}>
    |SELECT DISTINCT ?spec ?retryCount ?lastRetryTime
    |WHERE {
    |  GRAPH ?g {
    |    ?s nx:datasetSpec ?spec .
    |    ?s nx:harvestInRetry "true" .
    |    OPTIONAL { ?s nx:harvestRetryCount ?retryCount }
    |    OPTIONAL { ?s nx:harvestLastRetryTime ?lastRetryTime }
    |  }
    |}
    |ORDER BY ?lastRetryTime
   """.stripMargin
```

---

### Phase 5: Update UI for Retry Status

#### 5.1 Update Dataset Template

**File**: `public/templates/dataset-list.html`

Replace the error display section (around line 329) with retry-aware version:

```html
<!-- Retry status indicator (non-blocking) -->
<div data-ng-if="dataset.inRetry" class="alert alert-warning" role="alert">
    <div class="pull-right btn-group">
        <button type="button" class="btn btn-xs btn-primary"
                data-ng-click="retryNow()"
                data-ng-disabled="datasetBusy">
            <i class="fa fa-refresh"></i> Retry Now
        </button>
        <button type="button" class="btn btn-xs btn-default"
                data-ng-click="stopRetrying()">
            <i class="fa fa-stop"></i> Stop Retrying
        </button>
    </div>
    <i class="fa fa-clock-o"></i>
    <strong>Harvest failed</strong> -
    Retrying in <span class="badge">{{ dataset.nextRetryMinutes }}</span> minutes
    (attempt #{{ dataset.retryCount }})
    <br/>
    <small class="text-muted">{{ dataset.retryMessage }}</small>
</div>

<!-- Permanent error (blocking, requires dismissal) -->
<div data-ng-if="dataset.datasetErrorMessage && !dataset.inRetry">
    <div class="alert alert-danger" role="alert">
        <button type="button" class="close" data-dismiss="alert"
                data-ng-click="clearError()" aria-label="Close">
            <span aria-hidden="true">&times;</span>
        </button>
        <i class="fa fa-exclamation-circle"></i>
        <span>{{ dataset.datasetErrorMessage | unsafe }}</span>
        <hr />
        You must
        <a href data-dismiss="alert" data-ng-click="clearError()"
           class="alert-link">close/clear</a>
        this error message to continue
    </div>
</div>
```

#### 5.2 Update JavaScript Controller

**File**: `app/assets/javascripts/datasetList/dataset-list-controllers.js`

Add retry status handling to `decorateDataset`:

```javascript
$scope.decorateDataset = function (dataset) {
    // ... existing code ...

    // Parse retry status
    dataset.inRetry = dataset.harvestInRetry === 'true';
    dataset.retryCount = parseInt(dataset.harvestRetryCount) || 0;
    dataset.retryMessage = dataset.harvestRetryMessage || '';

    // Calculate next retry time
    if (dataset.inRetry && dataset.harvestLastRetryTime) {
        var lastRetry = new Date(dataset.harvestLastRetryTime);
        var retryIntervalMs = (dataset.retryIntervalMinutes || 60) * 60 * 1000;
        var nextRetry = new Date(lastRetry.getTime() + retryIntervalMs);
        var now = new Date();
        var diffMs = nextRetry - now;
        dataset.nextRetryMinutes = Math.max(0, Math.round(diffMs / 60000));
    }

    // ... rest of existing code ...
};
```

Add retry command handlers in `DatasetEntryCtrl`:

```javascript
$scope.retryNow = function () {
    command("retry now", null);
};

$scope.stopRetrying = function () {
    command("stop retrying", "Stop automatic retry and show error?");
};
```

#### 5.3 Add Retry Fields to Harvest Info Template

**File**: `public/templates/dataset-list.html` (harvesting-info.html section)

Add retry information to the harvesting statistics:

```html
<ul class="list-group" data-ng-show="dataset.inRetry">
    <li class="list-group-item list-group-item-warning">
        <strong>Retry Status:</strong> Attempt #{{ dataset.retryCount }}
    </li>
    <li class="list-group-item">
        Last retry: {{ dataset.harvestLastRetryTime | date: 'yyyy/MM/dd HH:mm' }}
    </li>
    <li class="list-group-item">
        Next retry in: {{ dataset.nextRetryMinutes }} minutes
    </li>
    <li class="list-group-item">
        Error: {{ dataset.retryMessage }}
    </li>
</ul>
```

---

### Phase 6: WebSocket Updates for Retry Status

The existing WebSocket infrastructure already handles dataset updates. The retry status will be included in the JSON response automatically since it's stored as dataset properties that are serialized via `dsInfoWrites`.

No additional changes needed for real-time updates.

---

## Files Summary

| File | Changes |
|------|---------|
| `conf/application.conf` | Add `narthex.harvest.retryIntervalMinutes` setting |
| `app/organization/AppConfig.scala` | Load retry interval configuration |
| `app/triplestore/GraphProperties.scala` | Add retry-related RDF properties |
| `app/triplestore/Sparql.scala` | Add query for datasets in retry mode |
| `app/dataset/DsInfo.scala` | Add retry helper methods and list query |
| `app/dataset/DatasetActor.scala` | Add `InRetry` state and handlers |
| `app/harvest/PeriodicHarvest.scala` | Add retry checking logic |
| `public/templates/dataset-list.html` | Add retry status UI |
| `app/assets/javascripts/datasetList/dataset-list-controllers.js` | Add retry controls |

---

## Testing Plan

### Unit Tests

1. **DsInfo retry methods**
   - `setInRetry` sets correct properties
   - `incrementRetryCount` increments correctly
   - `clearRetryState` removes all retry properties
   - `isTimeForRetry` calculates correctly based on interval

2. **DatasetActor state transitions**
   - `WorkFailure` during harvest → `InRetry` state
   - `WorkFailure` during non-harvest → `InError` state (unchanged)
   - `HarvestComplete` in `InRetry` → `Dormant` (success)
   - `"stop retrying"` command → `InError` state
   - `"retry now"` command → triggers harvest

3. **PeriodicHarvest retry logic**
   - Finds datasets in retry mode
   - Respects retry interval timing
   - Acquires semaphore before retry
   - Handles semaphore unavailable gracefully

### Integration Tests

1. **Full retry flow**
   - Start harvest → simulate failure → verify retry state
   - Wait for retry interval → verify automatic retry triggered
   - Simulate success → verify retry state cleared

2. **Manual intervention**
   - Trigger "retry now" → verify immediate retry
   - Trigger "stop retrying" → verify error state and email

3. **UI display**
   - Verify retry status shows correctly
   - Verify countdown timer updates
   - Verify buttons work correctly

### Manual Testing Checklist

- [ ] Configure retry interval in application.conf
- [ ] Trigger harvest failure (disconnect server temporarily)
- [ ] Verify retry status appears in UI
- [ ] Wait for retry interval to pass
- [ ] Verify automatic retry occurs
- [ ] Test "Retry Now" button
- [ ] Test "Stop Retrying" button
- [ ] Verify successful harvest clears retry state
- [ ] Test multiple datasets in retry simultaneously

---

## Configuration Reference

### application.conf

```hocon
narthex.harvest {
  # Interval between retry attempts in minutes
  # Default: 60 (1 hour)
  retryIntervalMinutes = 60
}
```

### RDF Properties

| Property | Type | Description |
|----------|------|-------------|
| `harvestInRetry` | Boolean | Whether dataset is in retry mode |
| `harvestRetryCount` | Integer | Number of retry attempts made |
| `harvestLastRetryTime` | DateTime | Timestamp of last retry attempt |
| `harvestRetryMessage` | String | Error message that triggered retry |

---

## Future Enhancements

Potential future improvements not included in this implementation:

1. **Per-dataset retry configuration** - Allow each dataset to have custom retry interval
2. **Maximum retry limit** - Option to stop after N retries (currently unlimited)
3. **Exponential backoff** - Increase interval between retries progressively
4. **Retry categories** - Different retry behavior for different error types
5. **Retry history log** - Store complete retry attempt history for debugging
6. **Email notifications** - Send email after certain number of retries

---

## Related Documentation

- [AdLib Error Recovery Implementation](./adlib-error-recovery-plan.md) - Record-level error recovery for AdLib harvests
- [Narthex Architecture](../CLAUDE.md) - Overall system architecture

---

## Revision History

| Date | Version | Author | Description |
|------|---------|--------|-------------|
| 2025-11-18 | 1.0 | - | Initial proposal |
