# Rec-Def Version Management — Phase 2 (Per-Dataset Selection)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Each task ends with a human diff review step before commit.

**Goal:** Pin a specific rec-def version per dataset (overrides the prefix-level "current"). Add bulk switch action with compat checks. Tag default mappings with their target recdef hash.

**Builds on phase 1** — assumes `RecDefRepo` + REST + UI tab + `SipPrefixRepo(prefix, recDefFile, validationOpt, schemaVersionsValue)` constructor are in place.

---

## Decisions (re-applied from brainstorm 2026-05-04)

| Q | Decision |
|---|----------|
| 3 | Strict mapping compat check on switch + `force` flag escape |
| 4 | Hybrid bulk modal — set property by default; opt-in regen/fastSave; pre-flight per-dataset compat report |
| 5 | Tag default-mapping with `targetRecDefHash` + validate at apply-time |

---

## Architecture

### Per-dataset NXProp

```scala
// triplestore/GraphProperties.scala
val datasetRecDefVersionHash = NXProp("datasetRecDefVersionHash")
```

`DsInfo`:
- `def getRecDefVersionHash: Option[String] = getLiteralProp(datasetRecDefVersionHash)`
- Setter via existing `setMetadataProp` flow.
- JSON render: `"datasetRecDefVersionHash": ...`

**Resolution rule** (everywhere a SIP is generated/read):
```
val effectiveHash = dsInfo.getRecDefVersionHash
val prefixRepo = sipFactory.prefixRepo(prefix, effectiveHash)
// where prefixRepo(prefix, None) = current
//       prefixRepo(prefix, Some(hash)) = that specific version (or None if missing)
```

### `SipFactory.prefixRepo` overload

```scala
def prefixRepo(prefix: String, versionHashOpt: Option[String] = None): Option[SipPrefixRepo] = {
  versionHashOpt match {
    case None       => prefixRepos.find(_.prefix == prefix)            // current
    case Some(hash) => recDefRepo.getVersion(prefix, hash).map { resolved =>
      new SipPrefixRepo(
        prefix, resolved.recordDefinitionFile, resolved.validationFileOpt,
        resolved.version.schemaVersion, rdfBaseUrl, wsApi, orgId
      )
    }
  }
}
```

Call sites to thread the dataset's `recDefVersionHash` through:
- `SourceProcessor.scala:138, 148` — has `dsInfo` via `datasetContext.dsInfo`
- `SipRepo.copyWithSourceTo` callers (already get prefix repo from caller — no internal change needed if caller passes the right one)
- `AppController.recDefJson(prefix)` — adds optional `?spec=` query param so dataset detail view can preview its specific tree

### Per-dataset switch endpoint

`POST /narthex/app/dataset/:spec/rec-def-version`
Body: `{ "hash": "<hash>", "force": false }`
Behaviour:
1. Resolve target version via RecDefRepo. 404 if missing.
2. Load dataset's current mapping XML (DatasetMappingRepo current).
3. `Sip.loadRecDefTree(target.recordDefinitionFile)` → newTree.
4. `RecMapping.read(currentMappingXml, newTree)` — wrapped in Try.
5. On Failure + `force=false` → 422 with parse-error detail.
6. On Success (or `force=true`):
   - `recMapping.setSchemaVersion(new SchemaVersion(target.schemaVersion))`
   - re-set facts via existing `Sip.factsToMap(facts)` flow
   - `RecMapping.write(...)` → bytes
   - `DatasetMappingRepo.saveFromSipUpload(newBytes, prefix, Some(s"clone for rec-def ${target.hash}"))` → new dataset mapping version
   - `dsInfo.setLiteralProp(datasetRecDefVersionHash, target.hash)`
   - return `{ ok: true, newMappingHash, newRecDefHash }`

**Important:** the switch does NOT auto-regenerate the SIP. UI surfaces "regenerate SIP to apply" hint.

### Bulk endpoint

`POST /narthex/app/datasets/bulk-set-rec-def`
Body:
```json
{
  "datasets": ["spec1", "spec2"],
  "prefix": "edm",
  "hash": "<targetHash>",
  "force": false,
  "regenSip": false,
  "fastSave": false
}
```

Behaviour:
1. Per dataset: same per-dataset switch logic. Collect results.
2. If `regenSip=true` AND that dataset's switch succeeded: enqueue `start generating sip` via DatasetActor (throttled 200ms like `bulkFastSave`).
3. If `fastSave=true` AND succeeded: enqueue `start fast save from <state>`.
4. Return `{ results: [{ spec, ok, problem? }] }`.

`GET /narthex/app/datasets/bulk-rec-def-preflight?prefix=...&hash=...&datasets=spec1,spec2,...`
Returns per-dataset compat report (does mapping parse against new tree?). Used to populate the modal before user clicks Apply.

### Default mapping `targetRecDefHash` tagging

Add field to `DefaultMappingRepo.MappingVersion`:
```scala
case class MappingVersion(
  ...,
  targetRecDefHash: Option[String] = None  // backward-compat: existing entries are None
)
```

On `saveVersion`:
- Parse `<rec-mapping schemaVersion="X.Y.Z">` from XML.
- Look up RecDefRepo for the prefix to find a recdef whose `schemaVersion` matches.
- If found, set `targetRecDefHash`. Else None (best-effort).

UI: in dataset detail mapping selector, show warning icon when default mapping's `targetRecDefHash` ≠ dataset's `effectiveRecDefHash`.

### UI

**Mapping tab (per-dataset)**:
- New dropdown "Record definition version" listing versions for this dataset's prefix.
- Selected option = current effective version.
- Onchange → confirm modal with pre-flight result (does mapping compile against new tree?). Force toggle if not.
- On confirm → POST switch endpoint → reload tab.

**List view bulk dropdown**:
- Replace standalone `bulkFastSave` button with action picker:
  - "Run Fast Save" (existing)
  - "Set Rec-Def Version…" (new)
- "Set Rec-Def Version" opens modal:
  - Prefix selector (auto-populated from current selected datasets' prefixes; warn if mixed)
  - Version dropdown (lists RecDefRepo versions for that prefix)
  - Pre-flight section (auto-runs preflight endpoint, shows pass/fail per selected dataset)
  - Checkboxes: regen SIP, run fast save, force
  - Apply / Cancel

---

## Tasks

Ordering: NXProp foundation first; switch endpoint + UI per-dataset before bulk + default mapping tagging.

### Task 1 — NXProp + DsInfo plumbing
- `triplestore/GraphProperties.scala` — add `datasetRecDefVersionHash`
- `app/dataset/DsInfo.scala` — getter, setter via `setMetadataProp`, JSON render
- `app/triplestore/Sparql.scala` — include in dataset queries
- Tests: `DsInfoSerializationSpec` covers round-trip

### Task 2 — `SipFactory.prefixRepo(prefix, versionHashOpt)` overload + call-site threading
- `app/dataset/SipFactory.scala` — overload as designed
- `app/record/SourceProcessor.scala:138, 148` — pass `dsInfo.getRecDefVersionHash`
- `app/controllers/AppController.scala` — `recDefJson(prefix, spec?)` route gains optional spec
- Tests: `SipFactorySpec` covers overload behaviour

### Task 3 — Per-dataset switch endpoint + mapping clone
- `app/controllers/AppController.scala` — `POST /dataset/:spec/rec-def-version`
- Helper in `Sip` companion: `cloneMappingForNewTree(xml, newTree, newSchemaVersion, factsMap): Option[Array[Byte]]`
- Save new mapping version via `DatasetMappingRepo.saveFromSipUpload` (or equivalent)
- Tests: cover compat-pass, compat-fail, force-override, hash persistence

### Task 4 — Default mapping `targetRecDefHash` tagging
- `app/mapping/DefaultMappingRepo.scala` — add `targetRecDefHash: Option[String]` to `MappingVersion`
- On `saveVersion`: derive from XML root attr → match against RecDefRepo
- Backward-compat reads: `Option` defaults to None
- UI hint: warning icon in default-mapping selector when mismatch with dataset
- Tests: tagging on upload + upload of XML with no schemaVersion attr

### Task 5 — Mapping tab per-dataset version dropdown
- `app/assets/javascripts/dataset/...` — find mapping tab controller
- Add dropdown bound to RecDefRepo versions list
- Onchange → preflight call → confirm modal → switch endpoint → reload
- Manual smoke required (no automated UI tests for AngularJS)

### Task 6 — Bulk dropdown action in list view
- `app/assets/javascripts/datasetList/dataset-list-controllers.js` — convert `bulkFastSave` button into action picker
- Add `bulkSetRecDefVersion()` modal handler
- New endpoint: `POST /narthex/app/datasets/bulk-set-rec-def`
- New endpoint: `GET /narthex/app/datasets/bulk-rec-def-preflight`
- Tests: mock-based unit tests on controller backing logic

### Task 7 — Verification + version bump
- Manual end-to-end on dev org
- `make compile` + `sbt test`
- Bump to 0.8.9.0 (minor — feature)
- Update `.wolf/anatomy.md` + `cerebrum.md`

---

## Verification (success criteria)

- Dataset's `datasetRecDefVersionHash` round-trips through DsInfo.
- Per-dataset switch endpoint accepts compatible mapping, rejects incompatible (422), accepts incompatible with `force=true`.
- After switch + `start generating sip`, the resulting SIP zip contains the chosen recdef version's XML + matches in `<facts>`.
- Bulk preflight endpoint returns per-dataset pass/fail without mutating state.
- Bulk apply with `regenSip=true` triggers SIP regen on each successful target.
- Default mapping uploaded against schemaVersion=`X` gets `targetRecDefHash` populated when a matching RecDefRepo version exists.

## Non-goals

- Cross-prefix migration (e.g. dataset on `edm` switching to `ace`). Use existing `datasetMapToPrefix` selector.
- Auto-regenerating SIP without explicit user opt-in.
- UI-level XSD validation toggles per version.
- Removing factory dir from disk.
