# Design: SPARQL datasets — merge-datasets als eersteklas dataset-type

**Status: GEPARKEERD** (2026-08-20). Vastgelegd na de nk-acpt-upgrade; uitvoering pas na de
lopende feature-prioriteiten. Contactcontext: wo2.collectienederland.nl (org `nk`).

## Aanleiding

De nk-org draait een twee-laags-pipeline die vóór de registry/verificatie-architectuur is
gebouwd en er nu buiten valt:

```
normale datasets (narthex) --process+save--> hub3 --rdfStore--> Fuseki /nk-data
hub3 sparql-harvester (config/*.toml, 1-min tick)
    --WhereClause per narthex-datasetSpec--> gemergde graphs --> ES (eigen spec-namen)
```

Functionele kern die narthex niet kan: **cross-dataset-merging**. Records uit meerdere
bronnen (SNK-formulieren, RCE, BHG) vormen samen één NKRecord/dossier; de harvester bouwt
per subject een gemergde graph (6 niveaus dereference) en indexeert die als één record.

Het werkt (E2E geverifieerd 2026-08-20: save → +triples in /nk-data → harvester-tick →
ES-count bij), maar is onzichtbaar voor alle nieuwe machinerie: geen tellingen-bewaking,
geen webhooks, geen drift-detectie, geen reconcile, sturing via handmatig toml-editen.

## Concept

Een **sparql-dataset** is een dataset in narthex met als "bron" niet een OAI/AdLib-endpoint
maar een SPARQL-selectie over de output van andere (normale) datasets:

- `harvestType = "sparql"` met config: endpoint (default: de org-triple-store),
  WhereClause/subject-selectie, dereference-diepte, output-spec
- De dataset *declareert zijn inputs* (de normale datasets waaruit gemergd wordt) —
  vandaag impliciet in de toml-WhereClause (`VALUES ?spec {...}`), straks expliciet
- De harvester (in hub3) blijft de uitvoerder; narthex wordt eigenaar van configuratie,
  levenscyclus en verificatie — zoals bij elk ander dataset-type

### Waarom niet mergen in narthex zelf
Narthex mapt record→record binnen één dataset. Joins over datasets horen in de
graph-laag; de bestaande harvester doet dat goed. Verworpen.

## Gefaseerd plan

### Fase 1 — Observability (C-lite, ±1 dag, geen architectuurwijziging)
- Narthex leest de harvest-tomls (`harvestConfigPath`): mapping narthex-spec ↔
  harvest-spec + `TotalSizeSubjects` + `LastCheck`
- Index-stats toont per sparql-dataset: Fuseki-telling (COUNT over de WhereClause,
  goedkoop) vs ES-telling vs harvester-verwachting → drift zichtbaar op de bestaande
  pagina, zelfde iconen/patronen
- Source-count-sweep dekt bron→narthex; dit dekt narthex→Fuseki→ES

### Fase 2 — Notificaties (klein)
- Harvester stuurt na elke run een webhook-notificatie (zelfde endpoint/apiKey als
  index_verify): spec, subjects, indexed, errors → run-historie + warning-labels in UI

### Fase 3 — Levenscyclus & repair
- `POST /api/admin/reharvest/{orgID}/{spec}` in hub3 (programmatisch ForceHarvest) →
  drift boven drempel → gerichte herindexering (zelfde autoRepair-patroon + cooldown)
- Toml-configuratie beheerbaar vanuit narthex-UI (sparql-dataset aanmaken/bewerken);
  hub3 herlaadt configs

### Fase 4 — Registry-semantiek (beslispunt)
- Optioneel: registry per sparql-dataset (subjects als records, sent-state = geharvest)
  → volledige reconcile-pariteit. Pas beslissen na fase 1-3-ervaring.

## Per-org-configuratie vandaag (vastgelegd gedrag)

- nk: `narthex.registry.enabled = false` — ES is eigendom van de harvest-laag;
  index_verify/reconcile-semantiek (narthex-spec == ES-spec) geldt daar niet.
  Revision-sweep blijft het orphan-mechanisme via hub3/rdfStore.
- brabantcloud/datahub: registry aan, volledige verificatie-stack.

## Open vragen

1. Waar leeft de sparql-dataset-config canoniek: narthex (en gepusht naar hub3) of
   hub3-tomls (en gelezen door narthex)? Fase 1 kiest lezen; definitief bij fase 3.
2. Incremental harvest: `IncrementalWhereClause` is nu leeg — dossier-graphs wijzigen
   als een input-record wijzigt; wanneer is een subject "gewijzigd"? (revision-triple
   van de inputs volgen?)
3. Depublicatie-semantiek: input-record verdwijnt → dossier verandert of verdwijnt;
   wie stuurt de drop naar ES? (Nu: ForceHarvest-hersync.)
4. Naamruimte: harvest-specs (`snk-*`, `nk`) vs narthex-specs (`nk-snk-*`) — bij
   fase 3 gelijktrekken of expliciet gescheiden houden? Afnemers-impact op prod!
