# Design: Source Service — betrouwbare acquisitie als zelfstandige dienst

**Status: GEPARKEERD** (2026-08-21). Ontwerp-beslissingen vastgelegd met Sjoerd;
uitvoering na de lopende feature-prioriteiten. Aanleiding: Memorix bumpt de
OAI-datestamp niet bij wijzigingen in gelinkte records — incrementele harvests
zien die wijzigingen dus nooit, en de source-count-sweep detecteert alleen
áántal-drift, geen inhoud-drift.

## Kernidee

Een volledig zelfstandige dienst die de acquisitie-kant overneemt: continu
harvesten met eigen change-detectie (content-hash), en stroomafwaarts perfecte
semantiek serveren. Endpoint-quirks (datestamp-leugens, stalls, ontbrekende
completeListSize, dag-granulariteit, duplicaten) worden op één plek geabsorbeerd.

```
Memorix / arno / anet / AdLib …   (rommelig)
        ▼  sweeps per endpoint (native-incremental als hint + periodieke full pass)
┌────────────────────────────────────────────┐
│ Source Service (standalone Go-binary)      │
│  Source → Set → Records + Changelog        │
│  record: id, hash, payload(zstd, N versies)│
│  changelog: (seq, id, put|delete, hash, ts)│  ← de ruggengraat
└────────────────────────────────────────────┘
        ▼  API's
narthex-instanties (bc, nk, datahub, …)
```

De **changelog met consumer-cursors** is het hart: "wat is er veranderd sinds
seq N" is O(delta). Onze datestamps = het moment dat de content-hash wijzigde;
verdwenen records worden echte tombstones.

## Besliste ontwerpkeuzes (2026-08-21)

1. **Record-identiteit**: de service gebruikt het RAUWE endpoint-id
   (OAI-identifier / AdLib-priref). IdFilter/uniqueId-logica blijft in narthex
   bij consumptie. Service blijft dataset-agnostisch.
2. **Auth**: statische API-key per consumer (header), zelfde patroon als de
   indexing-webhook. Netwerktoegang aanvullend via firewall/nginx.
3. **Payload-historie**: **N versies per record** (retentie configureerbaar,
   bv. laatste 5 versies of 90 dagen) — audit + inhoudelijke diffs bij
   mapping-vragen. Opruimbeleid hoort bij v1.
4. **Uitval**: narthex **wacht + alarmeert** (bestaande retry-mode). Geen
   automatische fallback naar origin — voorkomt dubbele waarheidsbron en houdt
   quirk-code uit narthex.
5. **Scope v1**: **OAI-PMH + AdLib-webapi** (dekt Memorix, arno, anet, koha,
   Antwerpen ≈ alle huidige pijn). JSON en upload/dump-bronnen later.
6. **Interim**: periodieke full-refresh in narthex wordt WÉL gebouwd als
   overbrugging (zie onder) en blijft daarna nuttig als eerlijkheids-pas.

## API-lagen

1. **OAI-PMH-facade** — correcte from/until (op onze hash-datestamps),
   `deletedRecord=persistent`, altijd completeListSize (ook bij paginering),
   seconde-granulariteit. Compat-pad: narthex' harvestURL omzetten volstaat (P0).
2. **Change-feed** — `GET /sets/{set}/changes?since_seq=N&limit=` → ndjson
   `{seq, id, op, hash, payload?}`; drijft narthex-processing direct aan (P1).
3. **Stats & capabilities** — per set: counts, drift vs origin-claims,
   sweep-historie; per endpoint: Identify-rapport (deletedRecord, granularity),
   completeListSize-bij-paging, header-bruikbaarheid, stall-score. Het
   endpoint-capability-inventory valt hier gratis uit.
4. **Analyse** — pad-frequenties en value-stats worden incrementeel bij ingest
   bijgehouden (vervangt narthex' volledige tree-index-runs);
   `GET /sets/{set}/analysis`.

## Tech

Standalone **Go**-binary (eigen repo, bewust geen ikuzo-dependency), SQLite per
set + zstd-payloads (beproefd: narthex-registry doet 835k records moeiteloos;
bc-totaal ≈ 1,5–2G laatste-versie; ×3–5 met historie), systemd, multi-tenant.

## Integratie-fasering

| Fase | Wat | Narthex-wijziging |
|---|---|---|
| P0 | Service + OAI-facade; harvestURL → facade | geen |
| P1 | `harvestType=sourceService`: ProcessStage leest change-feed met cursor; alleen echte delta's door de mapping; SourceRepo/pockets per dataset uitfaseerbaar | klein, geïsoleerd |
| P2 | Source-analyse uit de service; narthex-analyzer met pensioen | UI-databron |
| P3 | Sparql-datasets (zie `sparql-datasets-design.md`) consumeren hun inputs uit dezelfde feed | convergentie |

## Interim (goedgekeurd, wordt gebouwd)

Periodieke full-refresh in narthex: per dataset een configureerbaar interval
(default org-breed, bv. 7 dagen) waarna de periodieke harvest éénmalig
`FromScratchIncremental` draait i.p.v. `ModifiedAfter`. De output-hash-diff in
de save houdt de index-druk minimaal (alleen echt gewijzigde records worden
verstuurd). Spreiding via de bestaande queue/concurrency-limiet. Dicht het
Memorix-gat vanaf nu; blijft na de service waardevol.

## Open punten voor de bouwfase

- Naamgeving van de dienst; repo-locatie
- Retentie-mechaniek historie (versies vs dagen; compaction)
- Backpressure/limieten op de change-feed bij grote inhaalslagen
- Beheer-UI (minimaal: per-endpoint status, sweep nu, capability-rapport)
- Migratiepad per org (bc eerst, dan datahub, nk het laatst — nk's
  sparql-laag raakt dit niet)
