# Bulk Update Dataset Prefix

**Date:** 2026-03-18
**Status:** Approved

## Problem

Datasets in Narthex have different schema prefix versions that have been consolidated into a single `ace_0.2.2` schema. There is no way to bulk-update the prefix for all datasets — each one must be changed manually through the UI. After changing the prefix, each dataset needs "Make SIP" triggered so it can be remapped.

## Design

### New API Endpoint

**Route:**
```
+ nocsrf
POST /narthex/app/bulk-update-prefix  controllers.AppController.bulkUpdatePrefix
```

**Request body:**
```json
{
  "prefix": "ace_0.2.2"
}
```

**Response:**
```json
{
  "prefix": "ace_0.2.2",
  "updated": 40,
  "skipped": 3,
  "failed": 2,
  "failedSpecs": ["problem-dataset-1"]
}
```

### Behavior

1. Parse and validate the `prefix` field from the JSON body
2. Load all dataset infos via `orgContext`
3. For each dataset:
   - Read current `datasetMapToPrefix` — skip if already set to target prefix
   - Update `datasetMapToPrefix` via `dsInfo.setSingularLiteralProps`
   - Send `refresh` command to the dataset actor
   - Send `start generating sip` command via `orgContext.orgActor ! DatasetMessage(spec, Command("start generating sip"))`
4. If a single dataset fails, log the error, skip it, continue with the rest
5. Return counts of updated, skipped, and failed datasets

### Queueing

The `start generating sip` command goes through the OrgActor, which handles semaphore acquisition and queueing. This means:
- Datasets queue properly and don't exceed the concurrency limit
- Progress shows up in the dashboard as if the user clicked "Make SIP" manually
- No custom throttling or scheduling needed

### Implementation

- **Single method** in `AppController.scala` alongside `setDatasetProperties`
- **One route line** in `conf/routes`
- **No new files, actors, services, or messages**

### Invocation

```bash
curl -u admin:PASSWORD -X POST \
  'https://HOST/narthex/app/bulk-update-prefix' \
  -H 'Content-Type: application/json' \
  -d '{"prefix": "ace_0.2.2"}'
```
