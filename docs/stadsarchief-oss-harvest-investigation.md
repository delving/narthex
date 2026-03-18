# Stadsarchief Oss - Harvest Failure Investigation

**Date:** 2026-03-09
**Dataset:** stadsarchief-oss
**Status:** Resolved (both stuck state and redirect issue fixed)

## Problem Summary

The dataset `stadsarchief-oss` became stuck in a "processing" state and could not be stopped or restarted from the Narthex UI. The interrupt button had no effect, and the dataset remained stuck for over two days (March 7-9).

Additionally, the harvest itself has been failing repeatedly since at least March 7 with the error:

> For set Beelddocumenten with url, the completeLisSize was reported to be 58031 but at cursor 9900 0 records were returned

## Investigation Findings

### 1. Stuck Processing State (Resolved)

The dataset appeared stuck because a concurrency semaphore was leaked. When a user action triggered a harvest command while the dataset was not in the expected state, Narthex acquired the semaphore but never released it because no work was actually started.

This has been fixed in commit `a8a1fe1f`. The semaphore is now properly released when a command does not result in actual work being started.

### 2. Harvest Failure - URL Redirect Issue

The configured harvest URL for this dataset is:

```
https://collecties.stadsarchiefoss.nl/atlantispubliek/oai.axd
```

However, the error messages in the Narthex logs contain links pointing to a different domain:

```
https://collecties.hicoss.nl/atlantispubliek/oai.axd
```

This indicates the OAI-PMH endpoint at `collecties.stadsarchiefoss.nl` is redirecting requests to `collecties.hicoss.nl`. Narthex follows the redirect for the initial request, but subsequent paginated requests (using OAI-PMH resumption tokens) are sent back to the original `collecties.stadsarchiefoss.nl` URL rather than the redirected `collecties.hicoss.nl` URL.

#### How OAI-PMH pagination works

OAI-PMH uses resumption tokens for pagination. The server returns records in pages of ~100, along with a resumption token. The client uses that token in the next request to fetch the next page.

#### What goes wrong

1. **Page 1:** Narthex requests `collecties.stadsarchiefoss.nl/...?verb=ListRecords&set=Beelddocumenten&metadataPrefix=edm`
2. The server redirects to `collecties.hicoss.nl/...`
3. Narthex follows the redirect and receives page 1 with a resumption token
4. **Page 2:** Narthex sends the resumption token back to the **original** URL `collecties.stadsarchiefoss.nl/...?verb=ListRecords&resumptionToken=...`
5. The server redirects again to `collecties.hicoss.nl`
6. This process repeats for 99 pages (9900 records), but eventually fails at the point where the server stops returning records

The repeated redirects may cause session or token handling issues on the server side. The fact that it works for 99 pages before failing suggests the server tolerates it for a while but eventually the resumption token becomes invalid or the session expires.

## Recommended Actions

### For the customer (Stadsarchief Oss / data provider)

**Option A (preferred):** Update the harvest URL in Narthex to point directly to the new domain. Change the configured URL from:

```
https://collecties.stadsarchiefoss.nl/atlantispubliek/oai.axd
```

to:

```
https://collecties.hicoss.nl/atlantispubliek/oai.axd
```

This avoids the redirect entirely and should resolve the harvest failure. This can be done in the Narthex UI by editing the dataset's harvest settings.

**Option B:** Ask the data provider (HIC Oss) to verify that:
- The redirect from `stadsarchiefoss.nl` to `hicoss.nl` is intentional and permanent
- Their OAI-PMH endpoint correctly handles resumption tokens when requests arrive via redirect
- The endpoint can serve all 58000+ records without interruption

### For Narthex (implemented)

The harvester has been updated to capture the final URL after any redirect and use that URL for all subsequent paginated requests. This makes the harvester resilient to URL changes and redirects in general. The fix is in `Harvesting.scala` `fetchPMHPage`.

## Timeline

| Date | Event |
|------|-------|
| March 7, ~10:56 | Harvest fails with "0 records returned at cursor 9900" |
| March 7, ~11:02 | Narthex enters retry mode, sends error email |
| March 7, ~17:09 | User clears error and triggers manual action |
| March 7, ~17:09 | Semaphore leak occurs - dataset becomes stuck |
| March 8-9 | Dataset remains stuck, periodic harvests blocked |
| March 9, ~10:55 | Multiple interrupt attempts fail (actor not in expected state) |
| March 9, ~12:27 | Stuck state resolved via resetToDormant command |
| March 9 | Semaphore leak bug fixed (commit a8a1fe1f) |
