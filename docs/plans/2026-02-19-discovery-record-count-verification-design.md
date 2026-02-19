# Discovery Record Count Verification

## Problem

The OAI-PMH `ListSets` response from endpoints like Memorix Maior returns all sets, including those with 0 records. This results in 1291 "new" sets shown in the discovery UI when only ~42 actually have records. This negates the purpose of dataset discovery.

## Solution

Add background record count verification using OAI-PMH `ListIdentifiers` with `completeListSize` from the `resumptionToken` element. Cache results to a JSON file, load cached data on subsequent visits, and offer a "Refresh Counts" button.

## Key Discovery

The Memorix Maior endpoint returns `completeListSize` in the `resumptionToken` of `ListIdentifiers` responses. A single request per set is enough to get the exact record count — no pagination needed.

Example response for an empty set:
```xml
<resumptionToken completeListSize="0" cursor="0"></resumptionToken>
```

## Data Model

### New status: "empty"

Sets are categorized into four states (checked in order):

1. **"ignored"** — user explicitly ignored
2. **"existing"** — already imported as a dataset
3. **"empty"** — not imported, verified to have 0 records
4. **"new"** — not imported, has records (count > 0) or count unknown (no cache)

Before verification, all unimported sets appear as "new". After verification, zero-record sets move to "empty".

### DiscoveredSet additions

```scala
case class DiscoveredSet(
  // ... existing fields ...
  recordCount: Option[Int] = None,
  countVerifiedAt: Option[DateTime] = None
)
```

### DiscoveryResult additions

```scala
case class DiscoveryResult(
  // ... existing fields ...
  emptySets: List[DiscoveredSet],
  countsLastVerified: Option[DateTime] = None,
  countsAvailable: Boolean = false
)
```

### Cache file: `{orgRoot}/oai-sources/{sourceId}-counts.json`

```json
{
  "sourceId": "memorix-maior-enb",
  "lastVerified": "2026-02-19T14:30:00.000Z",
  "counts": {
    "enb_05.documenten": 0,
    "enb.beeldmateriaal": 4,
    "enb.objecten": 1523
  },
  "errors": {
    "enb_05.broken": "HTTP 500: Internal Server Error"
  },
  "summary": {
    "totalSets": 1611,
    "newWithRecords": 42,
    "empty": 929
  }
}
```

Separate from source config to keep configs clean. Purely derived data, safe to regenerate.

## Backend Design

### SetCountVerifier (Akka Actor)

Handles background verification:

1. Receives "verify" message with source config and list of setSpecs to check
2. Iterates through sets sequentially, calling `ListIdentifiers?set=X&metadataPrefix=Y`
3. Parses `completeListSize` from `resumptionToken` element
4. Writes results to counts JSON file progressively (every ~50 sets) and at completion
5. Tracks progress: `{total, checked, withRecords, errors}`

**Throttling:**
- 2 requests/second (500ms delay between requests)
- Configurable per source: `verifyDelayMs: Option[Int]`
- Sequential processing, no parallel requests to same endpoint
- HTTP 503/429: back off 5 seconds, retry once, then record error

### API Endpoints

- `POST /discovery/sources/:id/verify` — starts background verification, returns `{ "status": "started", "totalToCheck": 423 }`
- `GET /discovery/sources/:id/verify-status` — returns `{ "status": "running|complete|idle", "checked": 150, "total": 423, "withRecords": 42, "errors": 3 }`

### Discovery Service Changes

`discoverSets` method modified to:

1. Classify sets as today (ignored/existing/new)
2. Load `{sourceId}-counts.json` if it exists
3. For each unimported, non-ignored set: if cached count is 0 → "empty", otherwise → "new"
4. Attach `recordCount` and `countVerifiedAt` to each DiscoveredSet
5. Return four lists: newSets, existingSets, ignoredSets, emptySets

No server-side filtering — frontend decides what to show.

### Source List Enrichment

`GET /discovery/sources` reads each source's counts cache and attaches `newSetCount` and `countsLastVerified` to each source in the response. Zero extra API calls for the sidebar badge.

## Frontend Design

### Summary Bar

```
1611 total sets | 42 new | 320 existing | 929 empty | 0 ignored
Last verified: Feb 19, 2026 14:30
```

### New Sets Panel

Existing table with added "Records" column showing `completeListSize`. Unknown counts show "—".

### Empty Sets Panel

New collapsible section (collapsed by default). No checkboxes (can't import empty sets). Can ignore from here.

### Verification Controls

- No cache: **"Verify Record Counts"** button (primary style)
- Cache exists: **"Refresh Counts"** button (default style) + last verified timestamp
- Running: progress bar "Checking... 150 / 423 (35%)", polls every 3 seconds

### Sidebar Badge

```
Dataset Discovery [42]
```

Badge on the sidebar nav item showing count of "new" sets with records. Loaded from source list response (no extra requests). Only shown when cache exists and count > 0.

## Files to Modify

### Backend (Scala)
- `app/discovery/OaiSourceConfig.scala` — add fields to DiscoveredSet, DiscoveryResult; add cache models
- `app/discovery/DatasetDiscoveryService.scala` — merge cached counts into discovery results
- `app/discovery/OaiSourceRepo.scala` — read/write counts cache file
- `app/discovery/OaiListSetsParser.scala` — add ListIdentifiers + completeListSize parsing
- `app/discovery/SetCountVerifier.scala` — **new** Akka actor for background verification
- `app/controllers/DiscoveryController.scala` — add verify/verify-status endpoints
- `conf/routes` — add new routes

### Frontend (AngularJS)
- `app/assets/javascripts/discovery/discovery-controllers.js` — verification controls, progress polling, empty sets handling
- `app/assets/javascripts/discovery/discovery-services.js` — new API calls
- `public/templates/discovery.html` — records column, empty sets panel, verification UI, progress bar
- `app/views/index.scala.html` — sidebar badge
