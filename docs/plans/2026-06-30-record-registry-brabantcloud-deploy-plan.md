# Record Registry — Brabantcloud Deploy Plan

**Date:** 2026-06-30
**Target:** `root@ingestion.brabantcloud.hubs.delving.io` (org `brabantcloud`)
**Branch:** `feat/record-registry` (5 commits ahead of `main`)
**Window:** Tonight

## What ships

Per-dataset SQLite registry (`records.db`) that tracks every record by content hash so the save path can:

1. Emit explicit `drop_records` bulk actions for OAI tombstones and full-run misses, replacing the old "depublish via revision sweep" race.
2. Eventually skip re-indexing records whose hash did not change (deferred — index-action diffing is **not** in this deploy).

Master kill switch: `narthex.registry.enabled` (default `true`).
Safety net: `narthex.registry.keepRevisionSweep` (default `true`) keeps the old `increment_revision` + `clear_orphans` cycle running alongside the registry for full harvests.

## Hard prerequisite — Hub3 must understand `drop_records`

The branch sends a new bulk-action line:

```json
{"dataset":"<spec>","orgId":"brabantcloud","action":"drop_records","ids":["…","…"]}
```

If Hub3 does not handle that action verb, those POSTs fail or are silently ignored and OAI tombstones never depublish.

**Block deploy until confirmed.** Checks:

- [ ] Hub3 PR / commit adding `drop_records` is merged to the version brabantcloud will be running tonight.
- [ ] Quick smoke test on staging or a dev dataset: POST one `drop_records` line by hand, confirm the listed records are gone from the index.

If unconfirmed → either delay the narthex deploy, or deploy with `narthex.registry.enabled=false` and flip the flag later.

## Tombstone backlog check (do this before deploy)

OAI tombstones previously sat unprocessed. After this deploy they actually depublish. If a dataset has a large backlog of `<header status="deleted">` entries, the first incremental run will mass-depublish.

Per dataset on the brabantcloud server:

```bash
ssh root@ingestion.brabantcloud.hubs.delving.io '
  cd /opt/hub3/brabantcloud/NarthexFiles/brabantcloud/datasets
  for d in */; do
    f="${d}source/deleted.ids"
    if [ -f "$f" ]; then
      c=$(wc -l < "$f")
      [ "$c" -gt 0 ] && printf "%-40s %s\n" "${d%/}" "$c"
    fi
  done | sort -k2 -n
'
```

Anything in the 1000+ range → flag with data owner before the deploy, or temporarily move/truncate `deleted.ids` for those specs so the first run does not surprise downstream.

## Pre-deploy steps (today, before the window)

1. **Confirm Hub3 drop_records support** (see above).
2. **Tombstone backlog audit** (see above).
3. **Snapshot Fuseki + datasets dir** in case rollback is needed:
   ```bash
   ssh root@ingestion.brabantcloud.hubs.delving.io '
     systemctl stop narthex && \
     rsync -a --delete /opt/hub3/brabantcloud/NarthexVersions/current/ /opt/hub3/brabantcloud/NarthexVersions/_pre-registry-snapshot/ && \
     systemctl start narthex
   '
   ```
   (Or rely on the existing `NarthexVersions/` history — old release dirs stay until cleaned.)
4. **Check the current deployed version** so we know what to roll back to:
   ```bash
   ssh root@ingestion.brabantcloud.hubs.delving.io 'readlink /opt/hub3/brabantcloud/NarthexVersions/current'
   ```
   Record it here: `___________________`
5. **Bump version** on the branch:
   - Edit `version.sbt` → next patch
   - Edit `app/assets/javascripts/main.js` → matching `urlArgs: "v=X.X.X.X"`
   - Commit `chore: bump version to X.X.X.X`
   - Tag `vX.X.X.X`

## Deploy steps

1. **Switch to the branch**, ensure clean tree:
   ```bash
   cd /home/kiivihal/code/scala/narthex
   git fetch
   git switch feat/record-registry
   git pull --ff-only
   git status   # clean
   ```
2. **Compile + test locally**:
   ```bash
   make compile
   sbt "testOnly services.RecordRegistrySpec"
   ```
3. **Deploy** (no restart, then explicit restart so we can watch logs come up):
   ```bash
   make deploy-no-restart SSH_HOST=root@ingestion.brabantcloud.hubs.delving.io ORG_ID=brabantcloud
   ```
4. **Verify** the new version landed before restart:
   ```bash
   ssh root@ingestion.brabantcloud.hubs.delving.io 'ls /opt/hub3/brabantcloud/NarthexVersions/ | tail -5'
   ```
5. **Set conservative flags** in `environment.conf` (apply before restart so the first boot honours them). Open
   `/opt/hub3/brabantcloud/NarthexFiles/environment.conf` and add:
   ```
   narthex.registry.enabled = true
   narthex.registry.keepRevisionSweep = true
   ```
   (Both are the defaults — explicit lines so future ops can flip without grepping the JAR.)
6. **Restart**:
   ```bash
   ssh root@ingestion.brabantcloud.hubs.delving.io 'systemctl restart narthex && journalctl -u narthex -f --no-pager'
   ```
   Watch for:
   - `narthex.registry.enabled: true`
   - `narthex.registry.keepRevisionSweep: true`
   - No stack traces during startup.

## Post-deploy verification

Pick one low-stakes dataset (suggest one with a small record count and recent harvest activity). Record its spec: `___________________`

1. **First save after restart (idle dataset)** — should be a no-op for the registry. Inspect:
   ```bash
   ssh root@ingestion.brabantcloud.hubs.delving.io '
     ls /opt/hub3/brabantcloud/NarthexFiles/brabantcloud/datasets/<spec>/records.db
     sqlite3 /opt/hub3/brabantcloud/NarthexFiles/brabantcloud/datasets/<spec>/records.db \
       "SELECT count(*) FROM records;"
   '
   ```
   Expect: file created, 0 rows.
2. **Trigger a Process + Save** on the test dataset from the UI. Watch logs for:
   - `Registry: marked N records missing for full run …` — should be `0` on first full run after deploy.
   - `Registry: emitting drop_records for N ids` — should match deleted.ids backlog you measured.
3. **Confirm Hub3 received drop_records** — check Hub3 logs or the index for one of the listed ids.
4. **Run incremental harvest** (if scheduled, or trigger manually). Confirm:
   - OAI tombstones from this run appear in `drop_records`.
   - Index reflects the depublication.
5. **Sanity check `records.db` after the run**:
   ```sql
   SELECT status, count(*) FROM records GROUP BY status;
   SELECT count(*) FROM runs;
   ```
   Expect: `seen` count == source record count; one `completed` row in `runs`.

## Rollout to remaining datasets

If the single-dataset run passes:
- Let scheduled harvests run overnight.
- Tomorrow morning, audit `journalctl -u narthex --since "12 hours ago" | grep -iE "registry|drop_records"`.
- Spot-check 2–3 random datasets' `records.db`.

If anything looks wrong, flip the kill switch (see Rollback).

## Rollback

### Soft rollback — disable registry, keep new code

Edit `/opt/hub3/brabantcloud/NarthexFiles/environment.conf`:
```
narthex.registry.enabled = false
```
Restart narthex. Save path returns to pre-Task-3 behaviour. `records.db` files stay on disk (no data loss when re-enabling later).

### Hard rollback — previous narthex release

```bash
ssh root@ingestion.brabantcloud.hubs.delving.io '
  cd /opt/hub3/brabantcloud/NarthexVersions
  ln -sfn narthex-<previous-version> current
  systemctl restart narthex
'
```
`records.db` files persist on disk — harmless to old code (it does not read them). If you later re-deploy, registry resumes mid-state. To wipe and restart clean, delete `records.db` per dataset before re-enabling.

## After-the-fact tuning (not for this deploy window)

Once a week of stable runs is in: flip `narthex.registry.keepRevisionSweep = false` to drop the old `increment_revision` + `clear_orphans` cycle. Hub3 then relies entirely on `drop_records` for depublication. **Do not do this tonight.**

## Open questions before the window

- [ ] Is the Hub3 `drop_records` change actually live on brabantcloud's Hub3 instance?
- [ ] Which dataset is the canary?
- [ ] Backlog of OAI tombstones across datasets — any surprise depublications expected?
- [ ] Is there a maintenance announcement to send the data owners before a potentially noisy first run?
