# Bulk Update Dataset Prefix — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an API endpoint to bulk-update `datasetMapToPrefix` on all datasets and queue Make SIP for each.

**Architecture:** Single new controller method in `AppController` that iterates all datasets, updates the prefix triple via `DsInfo.setSingularLiteralProps`, and sends `start generating sip` commands through the OrgActor (which handles semaphore queueing). One new route line.

**Tech Stack:** Play Framework 2.8, Scala 2.13, Akka actors, Fuseki triplestore

**Spec:** `docs/plans/2026-03-18-bulk-update-prefix-design.md`

---

## File Map

| Action | File | Purpose |
|--------|------|---------|
| Modify | `conf/routes` | Add `POST /narthex/app/bulk-update-prefix` route |
| Modify | `app/controllers/AppController.scala` | Add `bulkUpdatePrefix` method |

---

## Task 1: Add the route

**Files:**
- Modify: `conf/routes:56` (insert before/after set-properties block)

- [ ] **Step 1: Add the route**

Insert after line 56 (`set-properties` route):

```
+ nocsrf
POST        /narthex/app/bulk-update-prefix                                 controllers.AppController.bulkUpdatePrefix
```

- [ ] **Step 2: Compile to verify route is valid**

Run: `make compile`
Expected: Compilation error — `bulkUpdatePrefix` method does not exist yet. This confirms the route is wired.

- [ ] **Step 3: Commit**

```bash
git add conf/routes
git commit -m "chore: add bulk-update-prefix route"
```

---

## Task 2: Implement bulkUpdatePrefix

**Files:**
- Modify: `app/controllers/AppController.scala:743` (insert after `setDatasetProperties`)

- [ ] **Step 1: Add the method**

Insert after the `setDatasetProperties` method (after line 743 in AppController.scala):

```scala
def bulkUpdatePrefix = Action.async(parse.json) { request =>
  val prefix = (request.body \ "prefix").asOpt[String].getOrElse {
    return Future.successful(BadRequest(Json.obj("error" -> "Missing 'prefix' field")))
  }

  import dataset.DsInfo.listDsInfo
  listDsInfo(orgContext).map { datasets =>
    var updated = 0
    var skipped = 0
    var failed = 0
    var failedSpecs = List.empty[String]

    datasets.foreach { dsInfo =>
      try {
        val currentPrefix = dsInfo.getLiteralProp(datasetMapToPrefix).getOrElse("")
        if (currentPrefix == prefix) {
          skipped += 1
        } else {
          dsInfo.setSingularLiteralProps(datasetMapToPrefix -> prefix)
          orgContext.orgActor ! DatasetMessage(dsInfo.spec, Command("refresh"))
          orgContext.orgActor ! DatasetMessage(dsInfo.spec, Command("start generating sip"))
          updated += 1
          logger.info(s"Bulk prefix update: ${dsInfo.spec} '$currentPrefix' -> '$prefix'")
        }
      } catch {
        case e: Exception =>
          logger.error(s"Bulk prefix update failed for ${dsInfo.spec}: ${e.getMessage}", e)
          failed += 1
          failedSpecs = failedSpecs :+ dsInfo.spec
      }
    }

    logger.info(s"Bulk prefix update complete: prefix=$prefix, updated=$updated, skipped=$skipped, failed=$failed")
    Ok(Json.obj(
      "prefix" -> prefix,
      "updated" -> updated,
      "skipped" -> skipped,
      "failed" -> failed,
      "failedSpecs" -> failedSpecs
    ))
  }
}
```

Note: `datasetMapToPrefix` is already imported via `GraphProperties._` at the top of `AppController`. `DatasetMessage` and `Command` are also already imported.

- [ ] **Step 2: Compile**

Run: `make compile`
Expected: `[success]`

- [ ] **Step 3: Run tests**

Run: `sbt test`
Expected: All 55 tests pass (no existing tests touch AppController beyond MainController)

- [ ] **Step 4: Manual smoke test (on dev instance)**

```bash
# Start narthex locally
sbt run

# Call the endpoint
curl -u admin:admin -X POST \
  'http://localhost:9000/narthex/app/bulk-update-prefix' \
  -H 'Content-Type: application/json' \
  -d '{"prefix": "ace_0.2.2"}'
```

Expected: JSON response with updated/skipped/failed counts. Datasets in UI should show Make SIP queued/running.

- [ ] **Step 5: Commit**

```bash
git add app/controllers/AppController.scala
git commit -m "feat(api): add bulk-update-prefix endpoint"
```

---

## Task 3: Deploy and run on production

- [ ] **Step 1: Bump version**

Run: `make bump-version`

- [ ] **Step 2: Build and deploy**

```bash
make deploy SSH_HOST=root@ingestion.brabantcloud.hubs.delving.io ORG_ID=brabantcloud
```

- [ ] **Step 3: Execute bulk update**

```bash
curl -u admin:PASSWORD -X POST \
  'https://ingestion.brabantcloud.hubs.delving.io/narthex/app/bulk-update-prefix' \
  -H 'Content-Type: application/json' \
  -d '{"prefix": "ace_0.2.2"}'
```

- [ ] **Step 4: Monitor progress**

Watch the dataset list in the UI — datasets should queue for Make SIP and process through the semaphore system. Check logs for any failures:

```bash
ssh root@ingestion.brabantcloud.hubs.delving.io \
  'tail -f /var/log/hub3-brabantcloud/narthex.log | grep "Bulk prefix"'
```
