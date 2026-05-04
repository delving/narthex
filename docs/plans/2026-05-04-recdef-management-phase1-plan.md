# Rec-Def Version Management — Phase 1 (Upload + Storage)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement task-by-task. Each task ends with a human diff review step before commit, per `CLAUDE.md`.

**Goal:** Let admin users upload, list, and manage record-definition (recdef) XML versions per prefix via a new "Rec-Defs" tab — eliminating the SCP-to-server step. Each prefix supports many versions side-by-side; one is marked "current" and used by all datasets on that prefix.

**Phase 1 scope (this plan):**
- New `RecDefRepo` storage class.
- One-shot migration of existing factory dir into the new layout.
- `SipPrefixRepo` resolves recdef + xsd from the new layout.
- Admin REST endpoints (mirror Default Mappings).
- New AngularJS tab `recdefs` (mirror `defaultMappings`).
- Admin auth.

**Out of scope (phase 2, separate plan):**
- `datasetRecDefVersionHash` per-dataset property.
- Mapping compat-check on version switch.
- Bulk dropdown action in dataset list view.
- Default-mapping ↔ recdef-version tagging.

**Tech Stack:** Scala 2.13, Play 2.8, ScalaTest, AngularJS 1.3. Build via `make compile`. Tests via `sbt "testOnly mapping.RecDefRepoSpec"`.

---

## Decisions captured (from brainstorm 2026-05-04)

| Q | Decision |
|---|---------|
| Versioning model | Side-by-side: many versions per prefix, one flagged "current" |
| XSD pairing | Lenient — recdef alone OK; metadata flag `hasXsd: false` |
| Compat check on switch | Strict + force-flag (deferred to phase 2) |
| Bulk action | Hybrid modal, property-only default (deferred to phase 2) |
| Default-mapping coupling | Tag `targetRecDefHash` + validate at apply (deferred to phase 2) |
| Factory dir fate | Migrate once + deprecate (phase 1 reads from new layout) |
| Permissions | Admin-only, same as Default Mappings |

---

## Architecture

### Storage layout

```
~/NarthexFiles/<org>/
  factory/<prefix>/                          # existing — read-only after migration
    <prefix>_<version>_record-definition.xml
    <prefix>_<version>_validation.xsd

  rec-defs/<prefix>/                         # NEW
    versions/
      20260504_120000_a1b2c3d4_edm_5.2.6.xml      # recdef
      20260504_120000_a1b2c3d4_edm_5.2.6.xsd      # paired xsd (optional)
      20260504_140000_e5f6g7h8_edm_5.2.7.xml
    metadata.json
```

`metadata.json`:
```json
{
  "prefix": "edm",
  "currentVersion": "a1b2c3d4",
  "versions": [
    {
      "hash": "a1b2c3d4",
      "schemaVersion": "edm_5.2.6",
      "filename": "20260504_120000_a1b2c3d4_edm_5.2.6.xml",
      "xsdFilename": "20260504_120000_a1b2c3d4_edm_5.2.6.xsd",
      "hasXsd": true,
      "uploadedAt": "2026-05-04T12:00:00Z",
      "source": "migration_from_factory",
      "notes": "Migrated from factory dir on first run"
    }
  ]
}
```

**Filename format:** `<timestamp>_<hash>_<prefix>_<schemaVersion>.xml`. Timestamp + hash mirror `DefaultMappingRepo`. `<prefix>_<schemaVersion>` carried so `SipPrefixRepo.schemaVersions` can be derived without re-parsing the XML.

### Migration

On first call to `SipFactory.prefixRepos` (lazy), `RecDefRepo.migrateFromFactory(factoryDir, recDefsDir)` runs idempotently:

1. Skip if `rec-defs/<prefix>/metadata.json` already exists.
2. For each prefix dir in `factory/`:
   - Locate `*_record-definition.xml` and `*_validation.xsd`.
   - Hash the XML content (SHA-256, first 8 hex chars — same as `DefaultMappingRepo.computeHash`).
   - Copy both files into `rec-defs/<prefix>/versions/` with new filename.
   - Write `metadata.json` with this version flagged as `currentVersion`.
3. Factory dir untouched (kept as recovery path; deprecated).

### `SipPrefixRepo` source change

Today (`app/dataset/SipFactory.scala:121-127`):
```scala
class SipFactory(home: File, ...) {
  lazy val prefixRepos = home.listFiles().filter(_.isDirectory).map(...new SipPrefixRepo(home, ...))
}
```

Tomorrow:
```scala
class SipFactory(factoryDir: File, recDefsDir: File, ...) {
  // Phase 1: still one prefix repo per prefix, pointed at currentVersion's files.
  // Phase 2 will add: prefixRepo(prefix, versionHashOpt).
  private lazy val recDefRepo = new RecDefRepo(recDefsDir)
  recDefRepo.migrateFromFactoryOnce(factoryDir)
  lazy val prefixRepos = recDefRepo.listPrefixes().flatMap { prefix =>
    recDefRepo.getCurrent(prefix).map { v =>
      new SipPrefixRepo(prefix, v.recordDefinitionFile, v.validationFileOpt, ...)
    }
  }
}
```

`SipPrefixRepo` constructor changes: instead of receiving a `home` directory, receive `(prefix, recDefFile, xsdFileOpt)` directly. `recordDefinition`, `validation`, `schemaVersions` lazy vals derived from those.

### REST API (mirror `default-mappings`)

```
GET    /narthex/app/rec-defs                                  list all prefixes + version counts
GET    /narthex/app/rec-defs/:prefix                          list versions for one prefix
GET    /narthex/app/rec-defs/:prefix/:hash/xml                download recdef XML for one version
GET    /narthex/app/rec-defs/:prefix/:hash/xsd                download xsd (404 if hasXsd=false)
POST   /narthex/app/rec-defs/:prefix/upload                   upload (multipart: recdef file + optional xsd file + notes)
POST   /narthex/app/rec-defs/:prefix/set-current              { hash } → set current version
DELETE /narthex/app/rec-defs/:prefix/versions/:hash           delete (refuse if hash == currentVersion)
```

All endpoints under `MainController`'s admin auth gate (same predicate as `default-mappings` routes).

### Frontend module

```
app/assets/javascripts/recdefs/
  main.js
  recdefs-services.js
  recdefs-controllers.js

app/views/recdefs/list.scala.html       (or routed via main SPA — match defaultMappings approach)
```

Mirror `defaultMappings`:
- List view: prefixes table (prefix, version count, current version, last uploaded).
- Detail view per prefix: versions table with [Set Current] [Download XML] [Download XSD] [Delete] actions, upload form.

Add nav link in main layout next to "Default Mappings".

---

## File Inventory

**New files:**
- `app/mapping/RecDefRepo.scala`
- `app/assets/javascripts/recdefs/main.js`
- `app/assets/javascripts/recdefs/recdefs-services.js`
- `app/assets/javascripts/recdefs/recdefs-controllers.js`
- `app/views/recdefs/list.scala.html` (or inlined SPA template)
- `test/mapping/RecDefRepoSpec.scala`
- `test/controllers/RecDefControllerSpec.scala`

**Modified files:**
- `app/dataset/SipFactory.scala` — `SipFactory` constructor accepts `recDefsDir`; `SipPrefixRepo` constructor changes; lazy migration call.
- `app/organization/OrgContext.scala:62-76` — wire `recDefsDir = new File(orgRoot, "rec-defs")`.
- `app/controllers/AppController.scala` — new endpoints + lazy `recDefRepo`.
- `conf/routes` — new route block.
- `app/assets/javascripts/main.js` (RequireJS) — register `recdefs` module.
- Main layout template — new nav link.

---

## Task Ordering

Tasks 1–3 build the core (storage + migration + factory wiring). Tasks 4–6 expose the API + UI. Tasks 7–8 are tests + docs.

Migration (Task 2) must land **before** the factory wiring change (Task 3) ships — otherwise existing deploys lose recdef visibility on first restart.

---

## Task 1: `RecDefRepo` storage class

Mirror `DefaultMappingRepo` shape: case classes for version + light info + prefix grouping; same hash + filename helpers.

**Files:**
- Create: `app/mapping/RecDefRepo.scala`
- Create: `test/mapping/RecDefRepoSpec.scala`

**Scope:**
```scala
object RecDefRepo {
  val VERSIONS_DIR = "versions"
  val METADATA_FILE = "metadata.json"
  val TIMESTAMP_FORMAT = DefaultMappingRepo.TIMESTAMP_FORMAT
  val computeHash = DefaultMappingRepo.computeHash _   // reuse

  case class RecDefVersion(
    hash: String,
    schemaVersion: String,        // e.g. "edm_5.2.6"
    filename: String,
    xsdFilename: Option[String],
    uploadedAt: DateTime,
    source: String,                // "upload" | "migration_from_factory"
    notes: Option[String]
  )

  case class PrefixMetadata(
    prefix: String,
    currentVersion: Option[String],
    versions: List[RecDefVersion]
  )

  case class RecDefVersionResolved(
    version: RecDefVersion,
    recordDefinitionFile: File,
    validationFileOpt: Option[File]
  )

  // JSON formats reuse DefaultMappingRepo's DateTime formats
}

class RecDefRepo(recDefsDir: File) {
  def migrateFromFactoryOnce(factoryDir: File): Unit
  def listPrefixes(): List[String]
  def listVersions(prefix: String): List[RecDefVersion]
  def getCurrent(prefix: String): Option[RecDefVersionResolved]
  def getVersion(prefix: String, hash: String): Option[RecDefVersionResolved]
  def saveVersion(prefix: String, recDefXml: String, xsdXmlOpt: Option[String], source: String, notes: Option[String]): RecDefVersion
  def setCurrent(prefix: String, hash: String): Boolean
  def deleteVersion(prefix: String, hash: String): Boolean   // refuse if current
}
```

`saveVersion` rules:
- Compute hash from XML content. Reject if hash already exists for this prefix (idempotent re-upload returns existing version unchanged).
- Parse `<schema name="..." version="..."/>` from XML to derive `schemaVersion = "<name>_<version>"`. Required; reject upload if absent or unparseable.
- Filename: `${TIMESTAMP_FORMAT.print(now)}_${hash}_${schemaVersion}.xml`.
- If xsd present, write paired file with same prefix-stem + `.xsd`.
- First version uploaded for a prefix → automatically set as current.

**Tests (new file):**
- [ ] **Step 1: Failing test — saveVersion stores XML + writes metadata.json**

```scala
class RecDefRepoSpec extends AnyFlatSpec with should.Matchers {
  // helper to make tmpDir, fixture XML with <schema name="edm" version="5.2.6"/>
  it should "store recdef XML + metadata on first save" in withTempDir { dir =>
    val repo = new RecDefRepo(dir)
    val v = repo.saveVersion("edm", fixtureXml("edm", "5.2.6"), None, "upload", Some("test"))
    v.schemaVersion shouldBe "edm_5.2.6"
    v.hasXsd shouldBe false
    repo.getCurrent("edm").map(_.version.hash) shouldBe Some(v.hash)
    new File(new File(new File(dir, "edm"), "versions"), v.filename) should exist
  }
}
```

- [ ] **Step 2: Implement `saveVersion`, `getCurrent`, JSON read/write of metadata.json**
- [ ] **Step 3: Failing test — re-upload same XML returns existing version (idempotent)**
- [ ] **Step 4: Failing test — saveVersion rejects XML missing `<schema name=... version=...>`**
- [ ] **Step 5: Failing test — saveVersion with xsd writes paired file + sets `hasXsd = true`**
- [ ] **Step 6: Failing test — `setCurrent` updates metadata + `deleteVersion` refuses current hash**
- [ ] **Step 7: Run `make compile && sbt "testOnly mapping.RecDefRepoSpec"` — all green**
- [ ] **Step 8: Human diff review (skill: diff-review-before-task-commit). Wait for ack.**
- [ ] **Step 9: Commit `feat(rec-defs): RecDefRepo storage with versioned uploads`**

---

## Task 2: Factory-dir migration

`migrateFromFactoryOnce(factoryDir)` is the only public side-effect entrypoint. Idempotent: skips any prefix already represented in `rec-defs/<prefix>/metadata.json`.

For each `factoryDir/<prefix>/` containing `*_record-definition.xml`:
1. Read XML + xsd (if present).
2. Call `saveVersion(prefix, xml, xsdOpt, source = "migration_from_factory", notes = Some(s"Migrated from $factoryDir/$prefix on $now"))`.
3. `setCurrent(prefix, version.hash)` (saveVersion auto-sets first version as current — no-op).

Logging: `INFO Migrating recdef factory/<prefix> → rec-defs/<prefix> (hash=...)` per prefix migrated, `INFO Skipping factory migration for <prefix> — already populated` otherwise.

**Files:**
- Modify: `app/mapping/RecDefRepo.scala`
- Modify: `test/mapping/RecDefRepoSpec.scala`

- [ ] **Step 1: Failing test — migrateFromFactoryOnce creates rec-defs/<prefix>/metadata.json + copies files**
- [ ] **Step 2: Failing test — migration is idempotent (running twice doesn't duplicate versions)**
- [ ] **Step 3: Failing test — factory prefix without xsd → migration succeeds, `hasXsd = false`**
- [ ] **Step 4: Failing test — factory prefix with malformed XML → migration logs + skips that prefix, others succeed**
- [ ] **Step 5: Implement `migrateFromFactoryOnce`. Tests pass.**
- [ ] **Step 6: Run `sbt "testOnly mapping.RecDefRepoSpec"` — all green**
- [ ] **Step 7: Human diff review. Wait for ack.**
- [ ] **Step 8: Commit `feat(rec-defs): factory-dir migration`**

---

## Task 3: Wire `SipFactory` + `SipPrefixRepo` to RecDefRepo

`SipFactory` constructor gains `recDefsDir`. `SipPrefixRepo` constructor changes from `(home: File, ...)` to `(prefix, recDefFile, xsdFileOpt, ...)`.

**Files:**
- Modify: `app/dataset/SipFactory.scala`
- Modify: `app/organization/OrgContext.scala:62-76` — pass `new File(orgRoot, "rec-defs")` to `SipFactory`.
- Modify: existing tests instantiating `SipPrefixRepo` directly.

**Risks:**
- `SipPrefixRepo.schemaVersions` lazy val today derived from filename. After change, derive from filename of the resolved recdef file (still works because filename includes `<prefix>_<schemaVersion>`).
- `SipPrefixRepo.recordDefinition` + `.validation` keep type `File` — but `validation` becomes `Option[File]`. All callers (`SipRepo.scala:484, 571, 575`, `SipFactory.scala:147, 232`) must handle `Option`. **Or:** keep `validation: File` and throw lazily on access if absent — matches current behaviour for missing xsd. Pick the latter to minimize churn; add a `validationOpt: Option[File]` accessor for new code.

**Phase 1 simplification:** Keep `validation: File` throwing-lazy. Phase 2 (per-dataset version) revisits XSD optionality per-version.

- [ ] **Step 1: Failing test — `SipFactory` constructed against tmp `recDefsDir` exposes prefix repos for current versions**
- [ ] **Step 2: Refactor `SipPrefixRepo` constructor + update `SipFactory.prefixRepos`**
- [ ] **Step 3: Update existing tests that build `SipPrefixRepo` directly (search for `new SipPrefixRepo` in `test/`)**
- [ ] **Step 4: Update `OrgContext` to pass `recDefsDir`**
- [ ] **Step 5: `make compile && sbt test` — all green (touches many tests; ensure none regress)**
- [ ] **Step 6: Manual smoke: start app with empty `rec-defs/`, confirm migration runs from existing factory dir, confirm `prefixRepos` returns expected prefixes**
- [ ] **Step 7: Human diff review. Wait for ack.**
- [ ] **Step 8: Commit `refactor(sip): resolve recdef + xsd via RecDefRepo`**

---

## Task 4: REST endpoints

Mirror `default-mappings` controller methods + routes.

**Files:**
- Modify: `app/controllers/AppController.scala` — add `recDefRepo` lazy val + endpoint methods.
- Modify: `conf/routes` — new route block under existing `default-mappings` block.

Endpoints:
- `listRecDefs` → JSON `{ prefixes: [ { prefix, currentVersion, versionCount, latestUpload } ] }`
- `listRecDefVersions(prefix)` → JSON `{ prefix, currentVersion, versions: [...] }`
- `getRecDefXml(prefix, hash)` → `application/xml`
- `getRecDefXsd(prefix, hash)` → `application/xml`, 404 if absent
- `uploadRecDef(prefix)` → multipart `{ recdef: file, xsd: file?, notes: string? }` → JSON RecDefVersion
- `setCurrentRecDef(prefix)` → JSON `{ hash }` → ok / 404
- `deleteRecDefVersion(prefix, hash)` → ok / 404 / 409 if current

Auth: same admin gate as `default-mappings` routes (check `MainController` for the admin predicate).

- [ ] **Step 1: Failing test in `RecDefControllerSpec` — `GET /narthex/app/rec-defs` returns prefixes JSON**
- [ ] **Step 2: Failing test — `POST /narthex/app/rec-defs/edm/upload` with multipart returns version JSON + persists**
- [ ] **Step 3: Failing test — `DELETE /narthex/app/rec-defs/edm/versions/<currentHash>` returns 409**
- [ ] **Step 4: Failing test — non-admin gets 401/403**
- [ ] **Step 5: Implement endpoints + routes**
- [ ] **Step 6: `sbt "testOnly controllers.RecDefControllerSpec"` — green**
- [ ] **Step 7: Human diff review. Wait for ack.**
- [ ] **Step 8: Commit `feat(rec-defs): REST endpoints for upload + version management`**

---

## Task 5: Frontend `recdefs` module

Mirror `app/assets/javascripts/defaultMappings/`:

- `recdefs-services.js`: `RecDefsService` with `list()`, `listVersions(prefix)`, `upload(prefix, recDefFile, xsdFile?, notes?)`, `setCurrent(prefix, hash)`, `delete(prefix, hash)`.
- `recdefs-controllers.js`: `RecDefsListCtrl` (top-level prefix table) and `RecDefVersionsCtrl` (per-prefix version detail).
- `main.js`: AngularJS module + `$routeProvider` entries `/recdefs` and `/recdefs/:prefix`.

Templates inlined or reuse DefaultMappings template idioms.

Add nav-link in main layout (locate by grepping for "Default Mappings"); inject after.

- [ ] **Step 1: Scaffold `recdefs/` directory mirroring `defaultMappings/`**
- [ ] **Step 2: Implement `recdefs-services.js`**
- [ ] **Step 3: Implement controllers + templates**
- [ ] **Step 4: Register module in `app/assets/javascripts/main.js`**
- [ ] **Step 5: Add nav link**
- [ ] **Step 6: Bump `urlArgs: "v=X.X.X.X"` in `main.js` per `CLAUDE.md`**
- [ ] **Step 7: Manual browser smoke: list prefixes, upload edm 5.2.7, see it appear, set current, delete a non-current version, confirm UI states**
- [ ] **Step 8: Human diff review. Wait for ack.**
- [ ] **Step 9: Commit `feat(rec-defs): admin UI tab + AngularJS module`**

---

## Task 6: Verification + docs

- [ ] **Step 1: End-to-end manual test on a dev org with `rec-defs/` empty:**
  1. Start app → migration runs → `rec-defs/edm/metadata.json` populated from factory.
  2. Open `/recdefs` → see edm listed with 1 version.
  3. Upload a second edm version (5.2.7 if available, else duplicate 5.2.6 with renamed schema for testing).
  4. Set new version as current.
  5. Existing dataset on edm → `start generating sip` → confirm new SIP bundles new recdef (`unzip -p latest.sip.zip edm_5.2.7_record-definition.xml | head`).
  6. Process + save → confirm no regressions.
- [ ] **Step 2: `sbt test` — full suite green**
- [ ] **Step 3: Update `.wolf/anatomy.md` with new files (RecDefRepo, recdefs JS module, RecDefControllerSpec)**
- [ ] **Step 4: Update `.wolf/cerebrum.md` with key learning: "factory dir is read-only after migration; recdef versions live in `rec-defs/<prefix>/`"**
- [ ] **Step 5: Bump version (`make set-version V=0.8.8.0` — minor bump, this is a feature)**
- [ ] **Step 6: Open PR with summary referencing this plan + Q&A from brainstorm**
- [ ] **Step 7: Manual deploy verification on staging org**

---

## Phase 2 hand-off

Phase 2 plan (separate doc, written after phase 1 ships) should cover:

1. `datasetRecDefVersionHash` NXProp + DsInfo plumbing.
2. `SipFactory.prefixRepo(prefix, versionHashOpt)` overload.
3. Mapping compat-check: `RecMapping.read(stored, newTree)` + force-flag escape hatch.
4. Mapping clone-on-switch: bump `<rec-mapping schemaVersion=...>` attr, re-set facts, save as new DatasetMappingRepo version.
5. Default mapping `targetRecDefHash` metadata + UI warnings.
6. Bulk dropdown action in dataset list view (mirror `bulkFastSave`):
   - Per-dataset compat report pre-flight.
   - Optional chained stages (regen SIP → fast save).
7. Mapping-tab dropdown for per-dataset version selection.

Phase 2 risk: changing `datasetRecDefVersionHash` on indexed datasets requires explicit re-process; UX must surface that.

---

## Verification (phase 1 success criteria)

- `make compile` clean.
- `sbt test` clean.
- Existing dev/staging org: app boot triggers one-shot migration; factory dir unchanged on disk; `rec-defs/<prefix>/metadata.json` exists for every prefix that had a factory entry.
- Uploading a new recdef via UI: file lands at `rec-defs/<prefix>/versions/<...>.xml`, metadata updated, list view reflects it.
- Setting a different version as current + regenerating SIP for one dataset: new SIP zip contains the new recdef + xsd, dataset processes successfully.
- Deleting a non-current version: file removed, metadata updated. Deleting current: 409.
- Non-admin gets 401/403 on write endpoints.

---

## Non-goals (explicit)

- Per-dataset version pinning. Phase 2.
- Mapping compat checks. Phase 2.
- Bulk action UI. Phase 2.
- Tagging default mappings with target recdef hash. Phase 2.
- Removing factory dir from disk. Phase 2 or later — kept as recovery path.
- Editing recdef XML in-browser. Out of scope (use external editor + re-upload).
