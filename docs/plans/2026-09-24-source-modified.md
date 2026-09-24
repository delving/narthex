# Carrying the source's modification date into the index

**Date:** 2026-09-24
**Status:** Proposal — explored, not built. Awaiting approval on #3598.
**Ticket:** [#3598](https://delving.plan.io/issues/3598)

## The question that started it

Heemkundige Kring Hilvarenbeek wants the five most recently added items on
their homepage. They asked for an API key, an endpoint and a date field.

Two of the three are easy: the API needs no key, and their sets are
`enb-401-beeldmateriaal` and `enb-401-titels`. The date field is the problem.

## Why we cannot answer it today

We record when *we* processed a record, not when the institution changed it.

`meta.modified` exists in the v2 API and is sortable, and after an ordinary
incremental harvest it does distinguish new records. But a full re-index gives
every record the same timestamp — which is exactly what happened this week, so
right now the whole dataset shares one value and "the last five" means nothing.

`records.last_changed_run_id` in the registry is better: a resend does not
change a record's content hash, so it survives one. But it does not survive a
mapping change. Measured on enb-401-beeldmateriaal: 1338 of 1341 records
"changed" on 19 September, which was our own mapping work, not the museum's.

The record's own date is not in the index at all.

## It is in the source

Every OAI-PMH record carries a datestamp in its header, and for this dataset
the spread is real — February 2025 through June 2026, not one bulk value:

```
14 records  2026-01-16
12 records  2026-01-20
11 records  2026-01-26
 8 records  2025-02-27
 7 records  2026-06-10
```

Narthex reads that header while harvesting and then discards it. The word
"datestamp" appears in the codebase only in two comments.

## Feasibility, checked rather than assumed

**The parser is already looking at the right place.** `source_facts.txt` for
this dataset says:

```
recordRoot = /OAI-PMH/ListRecords/record
uniqueId   = /OAI-PMH/ListRecords/record/header/identifier
```

The header sits inside the record boundary, and `uniqueId` is recognised by
comparing a path string in `PocketParser`. The datestamp is one sibling away,
at `/OAI-PMH/ListRecords/record/header/datestamp`. Capturing it is close to a
copy of the line that already captures the identifier — not a rebuild of the
parse chain.

**The route avoids the mappings.** Carrying it through the RDF would mean
per-dataset mapping work. Going through `records.db` into the bulk message
does not touch a single mapping.

**hub3 already has the pattern.** `Request` carries `ContentHash` the same
way; adding `SourceModified string \`json:"sourceModified,omitempty"\`` is one
line and backwards compatible, so an older Narthex keeps working. The protobuf
`Header` message runs to field 15, so 16 is a clean addition, and
`make protobuffer` regenerates.

**Sorting comes free.** hub3's v2 sort already handles `meta.`-prefixed fields
(`api.go`, the `strings.HasPrefix(sr.GetSortBy(), "meta.")` branch), so
`sortBy=meta.sourceModified` needs no new sort plumbing.

## Steps

1. `PocketParser` captures the datestamp the way it captures `uniqueId`.
2. `Pocket` gains a `sourceModified` field.
3. A `source_modified` column on `records` — the `ALTER TABLE records ADD
   COLUMN` migration pattern is already there for `last_changed_run_id`.
4. `ProcessedRepo` includes it in the bulk action.
5. hub3: field on `Request`, field 16 on the protobuf `Header`, set on
   `fg.Meta` in `createFragmentBuilder`.

Existing records get the value at the next harvest; a full re-harvest
backfills.

## The honest caveat

Narthex's own configuration already says it, in `NarthexConfig.scala`:

> *the honesty pass that catches endpoints (Memorix) that change record
> content without bumping the OAI datestamp*

Memorix does not always update the datestamp when content changes, which is
why Narthex detects changes by hashing instead. So `sourceModified` answers
"when did the institution last touch this" well enough for "what is new", and
should not be sold as an exact change log.

## Worth doing beyond this one request

Every institution can then put "our latest acquisitions" on its own site, and
the field is the natural answer to a question we have had to decline before.
Consider storing `contentChanged` alongside it — `runs.started_at` for
`last_changed_run_id` — which answers a different question we ask ourselves:
when did we last see this record actually change. Useful for discovery, and
for explaining drift.
