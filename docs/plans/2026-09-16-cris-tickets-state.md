# Stand open Cris-tickets — 2026-09-16

Werkdocument, NIET COMMITTEN. Momentopname na de fixronde van 9-16 sep,
om de rest later op te pakken.

## Gefixt, wacht op bevestiging van Cris

| # | Onderwerp | Wat gedaan | Wacht op |
|---|---|---|---|
| 3521 | Wikibase-harvest brabantse-gebouwen dood sinds 10 juli | Blazegraph commit-state hersteld + wdqs-updater-container teruggezet; keten end-to-end: 2947→2975 in index; ticket beantwoord 15-09 | bevestiging dat Fleurs publicaties zichtbaar zijn |
| 3564 | enb-101-bidprentje: 3434 records bleven online na depublicatie | Rootcause: drop_records raakte v1-legacy-index nooit (v2-veldnamen op legacy mapping). Code-fix hub3 926c05d3 (deployed acpt) + opschoning 4171 v1-orphans over 40 datasets; v1==v2 op alle 352. Beantwoord 16-09 | website-check (let op site-caching) |
| 3565 | enb-83-video: verwijderd record bleef op Instant Website; Wrong Count 1 | Zelfde oorzaak als 3564 (record zat nog in v1). Wrong Count stond al dagen goed toen ticket kwam — vermoedelijk andere dataset/momentopname; screenshot ontbrak op ticket. Beantwoord 16-09 | bevestiging |
| 3566 | Discovery-pill "1 beschikbaar" bij lege lijst | Drie stapelende bugs: counts-cache override van newSetCount (permanent stale — enb-101 telde eeuwig mee), importSets werkte lastResult niet bij, badge ververste alleen bij page-load. Narthex 0.8.9.123 (61537e83), deployed acpt, API geverifieerd 0. Beantwoord 16-09 | bevestiging na refresh |
| 3497 | Foute default mappings niet te verwijderen | Delete-knop gebouwd (.119); wrong-mapping-incident hersteld (aaec7c73 byte-identiek terug). DELETE gaf bij Cris "Forbidden" — vermoedelijk zijn corporate proxy | test vanaf ander netwerk; zo structureel: POST-tunnel bouwen |
| 3501 | Default mapping XML-upload doet niets | UX-fixes (bestandsnaam-feedback, spinner) + niet reproduceerbaar; cache-hypothese | hard-refresh-hertest |
| 3507 | 3 invalid records brabant-in-beelden | Uitleg + close-voorstel gepost | akkoord om te sluiten |
| 3498 | Last changed-kolom liep achter | Herontwerp: Last changed in kolom, "last checked" als hover (bbd1efd9, .122 live) | bevestiging |

## Nog niet opgepakt (actionable)

| # | Onderwerp | Inschatting |
|---|---|---|
| 3548 | Veldvolgorde bron niet gerespecteerd na indexeren (Steendrukmuseum; verwijst naar #952 uit 2020) | Hub3/indexeer-onderzoek: waar gaat volgorde van multi-valued velden verloren (mapping-engine? RDF-set-semantiek? ES-array?). VOLGENDE KANDIDAAT |
| 3542 | Links-tab toont interne acpt-URLs i.p.v. data.brabantcloud.nl | Klein: publieke base-URL configureerbaar maken voor Links-tab. Raakt DNS-besluit decommission-plan |
| 3572 | Statusvolgorde handmatig harvesten verwarrend (Mappable i.p.v. Processable na Make SIP; Analyze Source doet niets zonder sample) | UX/state-machine review DatasetStatusProjector; gereviewd, nog niet gebouwd |

## Gekoppeld aan lopende trajecten

- **3495** — Wikibase-beheeroverdracht van Sjors → zie
  `2026-09-16-wikibase-handoff-3495.md` (ARM/Hetzner/NixOS + fuseki +
  schone export-module). Formele haakje voor de al geplande migratie.
- **3544 + 3350** — Pica-termenlijsten: merge naar 2 buckets DONE
  (termen-poc, #3544 beantwoord 15-09); wacht op URI-prefix-overleg met
  Sjors (vr 18-09), daarna: mappings force-rebuild + catalogusdrafts
  prefix + NoT-catalog-PR (drafts klaar in omeka-s-automation
  docs/mappings/not/; verbatim gebouwen-queries draaien inmiddels op zpack)

## Backlog / on-hold (geen actie nu)

3214 (datasetregister, on hold), 3117 (DIW doorklik), 3109 (PDF-viewer,
on hold), 3052 (met-media-facet wens), 2646 (wensenlijst), 2215 (styling
data.brabantcloud.nl), 2169 (triplestore voor LD-frontend), 2108 (sessie
default mappings), 2090 (doorontwikkeling 2023-2030, on hold), 952
(oer-ticket veldvolgorde — heropent via 3548), 3277/3349 (Omeka/EMO,
feedback provided), 3475 (WP-plugin Amalia, bij Cris), 3441/3443/3570/3573
(bij Eric).

## Overige lopende zaken (niet-Cris)

- Decommission-plan oude servers: fase 0-3 voorgesteld, nog niet gestart;
  gebouwen-SPARQL-migratie wordt gesubsumeerd door #3495-plan
- Oss-vragendocument stond in /tmp (mogelijk weg na reboot) — zo nodig
  opnieuw genereren
- Pre-existing testfalen narthex main: RecordRegistrySpec
  (v3-migratietest) + PocketManifestSpec (bug-229, open)
- Datahub draait narthex .117; acpt .123 — deploy datahub bij gelegenheid
