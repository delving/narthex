# Cross-Dataset Activity Monitor - Implementation Guide

**Status**: Phase 1 Complete (Foundation) - Ready for Phase 2
**Feature Branch**: `feature/cross-dataset-activity-monitor`
**Target Version**: 0.9.0

## Overview

This feature adds a cross-dataset activity monitor that shows recent state changes across all datasets with focus on completions and failures. It uses a dedicated daily activity log file at the organization level for efficient aggregation and includes pagination for both cross-dataset and per-dataset views.

## Architecture

### Daily Aggregated Activity Log

The key design decision is maintaining a **daily aggregated activity log** at the organization level:

- **Location**: `~/NarthexFiles/<orgId>/activity/activity-YYYY-MM-DD.jsonl`
- **Dual Writing**: Each operation is logged to BOTH:
  1. Dataset-specific: `<orgId>/<spec>/activity.jsonl` (existing, for per-dataset view)
  2. Daily aggregate: `<orgId>/activity/activity-YYYY-MM-DD.jsonl` (new, for cross-dataset view)
- **Benefits**:
  - Constant-time queries (O(1) vs O(N) datasets)
  - Easy archiving (just move old daily files)
  - Backward compatible (dataset logs still work)
  - Efficient pagination

### Entry Format (Daily Aggregate)

```json
{
  "dataset": "spec1",
  "timestamp": "2025-12-01T13:45:23.123Z",
  "operation": "HARVEST",
  "status": "completed",
  "duration_seconds": 45.2,
  "recordCount": 1234,
  "trigger": "manual",
  "workflowId": "wf_123"
}
```

## Implementation Phases

### ✅ Phase 1: Foundation (COMPLETED)

**What was done:**

1. **Updated ActivityLogger Service** (`app/services/ActivityLogger.scala`)
   - All methods now accept `orgActivityDir: File` and `dataset: String`
   - Writes to both dataset-specific log AND daily aggregate
   - Helper method `getDailyActivityFile()` generates today's filename
   - Updated methods:
     - `startWorkflow()`
     - `logOperationStart()`
     - `logOperationComplete()`
     - `logOperationFailed()`
     - `completeWorkflow()`
     - `failWorkflow()`

2. **Updated DatasetContext** (`app/dataset/DatasetContext.scala`)
   - Added `orgActivityDir` field
   - Automatically creates directory on initialization
   - Available to all DatasetActor instances

**Testing Phase 1:**
```bash
# These changes are backward compatible but won't activate until DatasetActor is updated
# No functional changes until Phase 2
```

---

### 🔄 Phase 2: DatasetActor Integration (NEXT)

**Goal**: Update all DatasetActor activity logging calls to use the new dual-logging mechanism.

**Files to modify:**
- `app/dataset/DatasetActor.scala` (main actor)
- `app/harvest/PeriodicHarvest.scala` (if it logs directly)

**What needs to be done:**

Find and update ALL ActivityLogger calls to include the two new parameters:

**Pattern to find:**
```scala
ActivityLogger.logOperationStart(
  datasetContext.activityLog,
  operation,
  trigger
)
```

**Replace with:**
```scala
ActivityLogger.logOperationStart(
  datasetContext.activityLog,
  datasetContext.orgActivityDir,  // NEW
  dsInfo.spec,                     // NEW
  operation,
  trigger
)
```

**Locations to update** (approximate line numbers, verify with search):

1. **Lines ~425-440**: Operation start logging
   ```scala
   // In handleCommand or similar
   ActivityLogger.logOperationStart(
     datasetContext.activityLog,
     datasetContext.orgActivityDir,
     dsInfo.spec,
     operation,
     trigger
   )
   ```

2. **Lines ~1068-1092**: Operation complete logging
   ```scala
   ActivityLogger.logOperationComplete(
     datasetContext.activityLog,
     datasetContext.orgActivityDir,
     dsInfo.spec,
     operation,
     trigger,
     startTime,
     currentWorkflowId,
     recordCountOpt
   )
   ```

3. **Lines ~1163-1183**: Operation failed logging
   ```scala
   ActivityLogger.logOperationFailed(
     datasetContext.activityLog,
     datasetContext.orgActivityDir,
     dsInfo.spec,
     operation,
     trigger,
     startTime,
     errorMessage,
     currentWorkflowId
   )
   ```

4. **Workflow logging** (if present):
   ```scala
   ActivityLogger.startWorkflow(
     datasetContext.activityLog,
     datasetContext.orgActivityDir,
     dsInfo.spec,
     workflowId,
     triggerType,
     operations
   )

   ActivityLogger.completeWorkflow(
     datasetContext.activityLog,
     datasetContext.orgActivityDir,
     dsInfo.spec,
     workflowId,
     startTime,
     totalRecords
   )

   ActivityLogger.failWorkflow(
     datasetContext.activityLog,
     datasetContext.orgActivityDir,
     dsInfo.spec,
     workflowId,
     startTime,
     errorMessage,
     failedOperation
   )
   ```

**Search strategy:**
```bash
# Find all ActivityLogger calls
grep -n "ActivityLogger\." app/dataset/DatasetActor.scala
grep -n "ActivityLogger\." app/harvest/PeriodicHarvest.scala
```

**Testing Phase 2:**
```bash
# Compile
make compile

# Run and perform a manual operation (harvest, process, etc.)
# Verify TWO files are created:
ls -la ~/NarthexFiles/<orgId>/<spec>/activity.jsonl
ls -la ~/NarthexFiles/<orgId>/activity/activity-$(date +%Y-%m-%d).jsonl

# Check that daily aggregate includes dataset field
tail ~/NarthexFiles/<orgId>/activity/activity-$(date +%Y-%m-%d).jsonl
# Should see: {"dataset":"spec1", ...}
```

---

### 📡 Phase 3: Backend API Endpoints (AFTER Phase 2)

**Goal**: Create paginated endpoints for fetching activity data.

#### 3.1: Cross-Dataset Recent Activity Endpoint

**File**: `app/controllers/APIController.scala`

Add method:
```scala
def recentActivityAcrossDatasets(
    days: Option[Int] = Some(1),
    limit: Option[Int] = Some(50),
    offset: Option[Int] = Some(0),
    status: Option[String] = Some("all")
) = Action { request =>
  val daysToFetch = days.getOrElse(1)
  val pageSize = limit.getOrElse(50)
  val pageOffset = offset.getOrElse(0)
  val statusFilter = status.getOrElse("all")

  val activityDir = new File(orgContext.orgRoot, "activity")

  // Calculate date range
  val dates = (0 until daysToFetch).map { offset =>
    DateTime.now().minusDays(offset).toString("yyyy-MM-dd")
  }

  // Read files for each date
  val allActivities = dates.flatMap { date =>
    val file = new File(activityDir, s"activity-$date.jsonl")
    if (file.exists()) {
      Source.fromFile(file).getLines()
        .flatMap { line =>
          Try(Json.parse(line)).toOption
        }
        .toList
    } else {
      List.empty
    }
  }

  // Sort by timestamp (newest first)
  val sorted = allActivities.sortBy(json =>
    -(json \ "timestamp").as[String].toLong
  )

  // Apply status filter
  val filtered = if (statusFilter != "all") {
    sorted.filter(json =>
      (json \ "status").asOpt[String].contains(statusFilter)
    )
  } else {
    sorted
  }

  // Calculate totals
  val total = filtered.length
  val completed = sorted.count(json =>
    (json \ "status").asOpt[String].contains("completed")
  )
  val failed = sorted.count(json =>
    (json \ "status").asOpt[String].contains("failed")
  )
  val active = sorted.count(json =>
    (json \ "status").asOpt[String].contains("started")
  )

  // Apply pagination
  val paginated = filtered.slice(pageOffset, pageOffset + pageSize)

  Ok(Json.obj(
    "activities" -> paginated,
    "summary" -> Json.obj(
      "total" -> total,
      "completed" -> completed,
      "failed" -> failed,
      "active" -> active
    ),
    "pagination" -> Json.obj(
      "offset" -> pageOffset,
      "limit" -> pageSize,
      "total" -> total,
      "hasMore" -> (pageOffset + pageSize < total)
    )
  ))
}
```

**Add import if needed:**
```scala
import scala.io.Source
import scala.util.Try
import org.joda.time.DateTime
```

#### 3.2: Update Per-Dataset Activity Endpoint

**File**: `app/controllers/APIController.scala`

Update existing `activityLog` method to support pagination:

```scala
def activityLog(
    spec: String,
    limit: Option[Int] = Some(50),
    offset: Option[Int] = Some(0),
    status: Option[String] = Some("all")
) = Action(parse.anyContent) { implicit request =>
  val datasetContext = orgContext.dsInfo(spec)
  val activityFile = datasetContext.activityLog

  if (!activityFile.exists()) {
    NotFound("Activity log not found")
  } else {
    val pageSize = limit.getOrElse(50)
    val pageOffset = offset.getOrElse(0)
    val statusFilter = status.getOrElse("all")

    // Read and parse JSONL
    val allActivities = Source.fromFile(activityFile).getLines()
      .flatMap { line =>
        Try(Json.parse(line)).toOption
      }
      .toList
      .reverse  // Newest first

    // Apply status filter
    val filtered = if (statusFilter != "all") {
      allActivities.filter(json =>
        (json \ "status").asOpt[String].contains(statusFilter)
      )
    } else {
      allActivities
    }

    // Calculate totals
    val total = filtered.length
    val completed = allActivities.count(json =>
      (json \ "status").asOpt[String].contains("completed")
    )
    val failed = allActivities.count(json =>
      (json \ "status").asOpt[String].contains("failed")
    )
    val active = allActivities.count(json =>
      (json \ "status").asOpt[String].contains("started")
    )

    // Apply pagination
    val paginated = filtered.slice(pageOffset, pageOffset + pageSize)

    // Return as JSON (breaking change from raw JSONL)
    Ok(Json.obj(
      "activities" -> paginated,
      "summary" -> Json.obj(
        "total" -> total,
        "completed" -> completed,
        "failed" -> failed,
        "active" -> active
      ),
      "pagination" -> Json.obj(
        "offset" -> pageOffset,
        "limit" -> pageSize,
        "total" -> total,
        "hasMore" -> (pageOffset + pageSize < total)
      )
    ))
  }
}
```

#### 3.3: Update Routes

**File**: `conf/routes`

Add/update:
```
# Cross-dataset activity monitor
GET  /narthex/api/activity/recent         controllers.APIController.recentActivityAcrossDatasets(days: Option[Int], limit: Option[Int], offset: Option[Int], status: Option[String])

# Update existing per-dataset activity route
GET  /narthex/api/:spec/activity          controllers.APIController.activityLog(spec: String, limit: Option[Int], offset: Option[Int], status: Option[String])
```

**Testing Phase 3:**
```bash
# Compile
make compile

# Test cross-dataset endpoint
curl "http://localhost:9000/narthex/api/activity/recent?days=1&limit=10"

# Test per-dataset endpoint with pagination
curl "http://localhost:9000/narthex/api/spec1/activity?limit=10&offset=0"

# Test filtering
curl "http://localhost:9000/narthex/api/activity/recent?status=failed"
```

---

### 🎨 Phase 4: Frontend - Activity Monitor Button (AFTER Phase 3)

**Goal**: Add button to toolbar that opens the activity monitor modal.

#### 4.1: Add Button to Toolbar

**File**: `public/templates/dataset-list.html`

Find the toolbar section (around line 20-30, after "Open/Close All" button):

```html
<a href class="btn btn-info" data-ng-click="openActivityMonitor()">
    <i class="fa fa-history"></i> Activity Monitor
    <span class="badge badge-danger" ng-if="activityFailureCount > 0">
        {{ activityFailureCount }}
    </span>
</a>
```

#### 4.2: Add Badge Polling

**File**: `app/assets/javascripts/datasetList/dataset-list-controllers.js`

In `DatasetListCtrl`, add after existing initialization:

```javascript
// Initialize failure count
$scope.activityFailureCount = 0;

// Poll for failure count every 60 seconds
var activityPollInterval = $interval(function() {
    datasetListService.getRecentActivity(1, 1, 0, 'failed').then(function(response) {
        $scope.activityFailureCount = response.data.summary.failed || 0;
    });
}, 60000);

// Poll immediately on load
datasetListService.getRecentActivity(1, 1, 0, 'failed').then(function(response) {
    $scope.activityFailureCount = response.data.summary.failed || 0;
});

// Cleanup on destroy
$scope.$on('$destroy', function() {
    if (activityPollInterval) {
        $interval.cancel(activityPollInterval);
    }
});
```

#### 4.3: Add Service Method

**File**: `app/assets/javascripts/datasetList/dataset-list-services.js`

Add to `datasetListService`:

```javascript
getRecentActivity: function(days, limit, offset, status) {
    return $http.get('/narthex/api/activity/recent', {
        params: {
            days: days || 1,
            limit: limit || 50,
            offset: offset || 0,
            status: status || 'all'
        }
    });
}
```

**Testing Phase 4.1:**
- Button should appear in toolbar
- Badge should show failure count (if any)
- Clicking button should try to call `openActivityMonitor()` (will error until Phase 5)

---

### 🎨 Phase 5: Frontend - Activity Monitor Modal (AFTER Phase 4)

**Goal**: Create the cross-dataset activity monitor modal with pagination.

See detailed implementation in `ACTIVITY-MONITOR-MODAL-IMPLEMENTATION.md` (to be created - ~600 lines of HTML/JS).

**Key components:**
1. Modal template with tabs (All/Completed/Failed/Active)
2. Activity table with clickable dataset rows
3. "Load More" pagination button
4. Auto-refresh every 30 seconds
5. Time range selector (24h/7d/30d)
6. Status filtering

---

### 🎨 Phase 6: Frontend - Update Per-Dataset Modal (AFTER Phase 5)

**Goal**: Add pagination to existing per-dataset activity modal.

**File**: `app/assets/javascripts/datasetList/dataset-list-controllers.js`

Update `openActivityModal()` function to:
1. Accept pagination response format
2. Add "Load More" button handler
3. Track pagination state

See detailed implementation in existing modal around line 1013.

---

### 🎨 Phase 7: Styling (AFTER Phase 6)

**File**: `public/stylesheets/main.css`

Add:
```css
/* Activity monitor badge */
.badge-danger {
    background-color: #d9534f;
    color: white;
}

/* Activity table row hover */
.table-hover tbody tr:hover {
    background-color: #f5f5f5;
    cursor: pointer;
}

/* Status-based row highlighting */
tr.success {
    background-color: #dff0d8 !important;
}

tr.danger {
    background-color: #f2dede !important;
}

/* Activity monitor modal */
.modal-lg {
    width: 90%;
    max-width: 1200px;
}

/* Load more button */
.btn:disabled {
    cursor: not-allowed;
    opacity: 0.65;
}
```

---

## Testing Strategy

### Manual Testing Checklist

After each phase:

- [ ] **Phase 2**: Daily activity files are created
- [ ] **Phase 2**: Both dataset and org logs contain same entries
- [ ] **Phase 2**: Dataset field is present in org log
- [ ] **Phase 3**: API returns paginated data correctly
- [ ] **Phase 3**: Filtering by status works
- [ ] **Phase 3**: hasMore flag is correct
- [ ] **Phase 4**: Button appears and shows failure count
- [ ] **Phase 5**: Modal opens and displays activities
- [ ] **Phase 5**: Tab switching works
- [ ] **Phase 5**: Load More appends data
- [ ] **Phase 5**: Clicking dataset scrolls to it
- [ ] **Phase 6**: Per-dataset modal has pagination
- [ ] **Phase 7**: Styling looks correct

### Integration Testing

```bash
# Run a complete workflow
1. Start harvest for spec1
2. Wait for completion
3. Check both activity files exist
4. Open activity monitor
5. Verify entry appears in "All" tab
6. Switch to "Completed" tab - should show entry
7. Click on spec1 - should scroll to dataset
8. Open spec1's activity modal
9. Verify pagination works
10. Click "Load More" if > 50 entries
```

### Performance Testing

```bash
# Generate many activities
for i in {1..200}; do
  # Trigger harvest/process/save
done

# Test pagination performance
time curl "http://localhost:9000/narthex/api/activity/recent?limit=50&offset=0"
# Should be < 100ms

# Test with multiple days
time curl "http://localhost:9000/narthex/api/activity/recent?days=7&limit=100"
# Should be < 500ms
```

---

## Git Workflow

### Creating Feature Branch

```bash
# Commit Phase 1 changes to main
git add app/services/ActivityLogger.scala app/dataset/DatasetContext.scala
git commit -m "feat: add dual activity logging foundation for cross-dataset monitor

- Updated ActivityLogger to write to both dataset-specific and daily aggregate logs
- Added orgActivityDir to DatasetContext for organization-level activity tracking
- Prepared infrastructure for cross-dataset activity monitor feature

This is Phase 1 of the activity monitor feature. No functional changes yet
until DatasetActor is updated to use the new logging methods."

# Create feature branch for remaining work
git checkout -b feature/cross-dataset-activity-monitor

# As you complete each phase, commit:
git commit -m "feat(activity-monitor): Phase 2 - integrate dual logging in DatasetActor"
git commit -m "feat(activity-monitor): Phase 3 - add paginated API endpoints"
git commit -m "feat(activity-monitor): Phase 4 - add activity monitor button and polling"
# etc.

# When complete, merge back to main
git checkout main
git merge feature/cross-dataset-activity-monitor
```

### Recommended Commit Strategy

Each phase = 1 commit, so you have:
- Phase 1: Already committed to main
- Phase 2: 1 commit on feature branch
- Phase 3: 1 commit (3 endpoint changes together)
- Phase 4: 1 commit (button + polling)
- Phase 5: 1 commit (modal implementation)
- Phase 6: 1 commit (update existing modal)
- Phase 7: 1 commit (styling)
- Final: 1 commit (version bump to 0.9.0)

Total: ~8 commits on feature branch

---

## Current Status

### ✅ Completed (on main branch)

- ActivityLogger updated for dual logging
- DatasetContext provides orgActivityDir
- Directory auto-created on initialization
- No functional impact until Phase 2

### 🚧 Next Steps

1. **Immediate**: Create feature branch
2. **Phase 2**: Update DatasetActor (~1-2 hours)
3. **Phase 3**: Create API endpoints (~2-3 hours)
4. **Phases 4-7**: Frontend work (~4-6 hours)

**Total estimated time**: 8-12 hours

### 📝 Notes

- Feature is backward compatible (existing activity logs continue working)
- No data migration needed
- Can be developed incrementally and tested at each phase
- Frontend can be built/tested independently with mock data

---

## Rollback Plan

If needed to rollback Phase 1 changes:

```bash
# Revert ActivityLogger changes
git revert <commit-hash>

# Or manually:
# 1. Remove orgActivityDir and dataset params from all ActivityLogger methods
# 2. Remove orgActivityDir from DatasetContext
# 3. Existing activity logs continue working normally
```

---

## Future Enhancements

After initial release (v0.9.0), consider:

1. **Export functionality**: Download activity logs as CSV/Excel
2. **Advanced filtering**: By operation type, date range, trigger type
3. **Activity search**: Full-text search across all activities
4. **Activity statistics**: Charts/graphs of activity trends
5. **Email alerts**: Notify on repeated failures
6. **Archive management**: Automatic cleanup of old daily files
7. **Real-time updates**: WebSocket integration for live activity feed

---

## Contact & Questions

For questions about this implementation:
- See original plan in conversation context
- Reference approved design in `ExitPlanMode` call
- All design decisions documented in this file

Last updated: 2025-12-01
