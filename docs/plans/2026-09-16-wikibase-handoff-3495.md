# Wikibase-overdracht Brabantse Gebouwen (planio #3495) — handoff- en migratieplan

Status: concept voor het overdrachtsgesprek met Sjors. NIET COMMITTEN
(werkdocument). Geschreven 2026-09-16 op basis van het onderzoek tijdens
#3521 (WDQS-storing) en #3544 (termenlijsten).

## Wat er nu draait (vastgesteld, niet gegokt)

**Server:** DigitalOcean droplet `wikibase` (157.245.70.238),
gebouwen.brabantcloud.nl. Beheer nu bij Sjors; hij wil het overdragen —
dit hoort niet tot zijn vaste dienstverlening (#3495).

**Stack** (docker-compose in `/home/wikibase/docker-compose.yml`, containers
op traefik na 3 jaar oud):

| Container | Image | Rol |
|---|---|---|
| wikibase | wikibase/wikibase:1.34-bundle | MediaWiki+Wikibase (redactie-UI, ~505k triples aan data, laatste edits dagelijks) |
| mysql | mariadb:10.3 | MediaWiki-database (de echte bron!) |
| elasticsearch | wikibase/elasticsearch:6.5.4-extra | wiki-zoek |
| wdqs | wikibase/wdqs:0.3.40 | Blazegraph triple store (journal 200M) |
| wdqs-updater | wikibase/wdqs:0.3.40 | RecentChanges→RDF sync. **Fragiel gebleken**: journal ging 20-07 in kapotte commit-state, updater stierf, container was verwijderd; 25-08 heeft iemand wikibase herstart zonder de updater terug te zetten. Op 15-09 door ons hersteld (57 dagen achterstand via RecentChanges ingehaald) |
| wdqs-frontend, wdqs-proxy | latest | query-UI + proxy (`/proxy/wdqs/...` en `/query`) |
| traefik | v2.3 | TLS/routing |

**Afnemers van de SPARQL-endpoint** (alles wat bij migratie mee moet):
1. `k3-pipeline.py` op nave-prod (data.brabantcloud.nl), hourly cron:
   SPARQL → `mapping_full.xml` (narthex-harvest elke 6u → index) en
   `k3_schema.nt` (→ PUT naar fuseki k3-2 op old-ingestion → Termennetwerk
   via data.brabantcloud.nl/gebouwen/query)
2. kakugo/zpack op termen-poc: source `wikibase-gebouwen` haalt
   `k3_schema.nt` op (hourly) voor de gebouwen-bucket
3. Redactie (Fleur e.a.): wiki-UI voor invoer; incidenteel de query-UI

**Belangrijk voor de fuseki-vraag:** de pipeline-queries gebruiken GEEN
Blazegraph-extensies (geverifieerd: 0 hits op SERVICE / wikibase:label /
mwapi / GAS) — plain SPARQL 1.1 (CONSTRUCT, VALUES, OPTIONAL, FILTER).
De label-service en federatie van WDQS worden dus niet gebruikt door de
machinerie; alleen de query-frontend voor mensen leunt er eventueel op.

## Doel

Migreren naar een **ARM-server bij Hetzner (CAX) op NixOS** (algemene
flakes in de maak), met **Fuseki i.p.v. Blazegraph** als triple store als
dat kan, en als slotstap een **schonere export** (module) zodat de
decruft-CONSTRUCTs kunnen verdwijnen.

## Fase 0 — overdrachtsgesprek met Sjors (het #3495-gesprek)

Vragen die alleen hij kan beantwoorden:
- Toegang: DO-account/droplet-eigenaarschap, DNS-beheer gebouwen.brabantcloud.nl,
  eventuele backups die nu ergens draaien (mysqldump-cron? DO-snapshots?)
- Wat is er ooit aangepast t.o.v. stock wikibase-docker (LocalSettings-
  patches, extensies, de `/proxy/wdqs`-routing)?
- Wie heeft er 25-08 aan de containers gezeten (context wdqs-storing)?
- Vrijdag-overleg URI-prefix (#3544) raakt dit direct: entity-URIs zijn
  `http://gebouwen.brabantcloud.nl/entity/Q...` — bij migratie MOET het
  concept-URI-domein stabiel blijven of bewust her-strategiseerd worden.
  Eén beslissing, niet twee.
- Wil hij een schaduwperiode meedraaien na overdracht?

## Fase 1 — nieuwe server + verhuizing as-is (risico-arm)

1. Hetzner CAX (arm64) provisionen met NixOS + de flake-in-wording.
   Herbruikbaar: hcloud-provisioning-kennis uit de kakugo/termen-poc
   scripts (omeka-s-automation repo); nieuw: NixOS-image i.p.v. Debian.
2. Stack als NixOS-config: `virtualisation.oci-containers` voor de
   bestaande images (arm64-check! wikibase 1.34-images zijn amd64-era —
   mogelijk qemu-emulatie of nieuwere multi-arch images nodig; DIT IS HET
   EERSTE DAT GETEST MOET WORDEN op een wegwerp-CAX) of — beter — meteen
   naar een recente wikibase-release (1.43+ bundle is multi-arch) met de
   MediaWiki-upgrade-path (1.34→recent gaat via mysqldump + update.php,
   stapsgewijs; testen met de echte dump).
3. Data over: mysqldump (bron van waarheid) + images/uploads; ES-index en
   triple store zijn afgeleiden en worden opnieuw opgebouwd.
4. Schaduwdraaien: nieuwe box naast oude, redactie test, dan DNS-switch
   gebouwen.brabantcloud.nl. Oude droplet 30 dagen bewaren.

## Fase 2 — Blazegraph → Fuseki

Voorwaarde uit fase 1: pipeline-queries zijn plain SPARQL (bewezen).

1. Fuseki 5.x/TDB2 dataset `gebouwen` op de nieuwe box (zelfde recept als
   nk-acpt: klein, weekly compact-cron; 505k triples ≈ enkele honderden MB).
2. Initieel laden: wikibase `dumpRdf.php` → nq → `tdb2.tdbloader`.
3. Incrementele sync — het echte werk. Opties, in volgorde van voorkeur:
   a. **Eigen kleine updater** (cron, python): RecentChanges-API pollen →
      per gewijzigde entity `Special:EntityData/Qxx.ttl` ophalen → in
      Fuseki: `DELETE WHERE { <entity> ... }` + INSERT (of per-entity
      named graph, dan is het een graph-PUT — schoner). ~100 regels,
      geen Blazegraph-erfenis, zelfde principe als wdqs-updater maar
      zonder diens fragiliteit.
   b. wdqs-updater tegen Fuseki richten (praat SPARQL Update) — minder
      werk maar sleept de oude java-stack mee; alleen als (a) tegenvalt.
4. `/proxy/wdqs/.../sparql`-pad in de routing naar Fuseki laten wijzen
   (query-compatibiliteit voor k3-pipeline zonder config-wijziging), of
   nette nieuwe endpoint + k3-pipeline config.py aanpassen (1 regel).
5. Verificatie: triple-count dump vs Fuseki; k3-pipeline-run levert
   byte-identieke mapping_full.xml/k3_schema.nt; edit in wiki → binnen
   sync-interval zichtbaar in Fuseki → volgende pipeline-run pikt op.
6. Wat vervalt: wdqs, wdqs-updater, wdqs-proxy, wdqs-frontend (evt. YASGUI
   tegen Fuseki als redactie een query-UI wil). Bonus: Fuseki UI heeft er al één.

## Fase 3 — schonere export (de "module")

Probleem nu: de ruwe wikibase-RDF zit vol statement-/reference-/value-nodes
(cruft); k3-pipeline decruft met grote SPARQL CONSTRUCTs die moeilijk te
onderhouden zijn (900+ regels query in k3-pipeline.py).

Aanbevolen richting: **niet nóg betere CONSTRUCTs, maar een andere bron.**
Wikibase's entity-JSON (`Special:EntityData/Qxx.json` / wbgetentities) is
de cruft-vrije representatie: claims → property → values, direct. Een
kleine export-module (standalone service of onderdeel van de sync uit
fase 2, zelfde codebase) die per entity JSON → schone triples (schema.org)
en/of XML produceert:
- vervangt de CONSTRUCT-decruft in k3-pipeline (mapping_full.xml direct
  uit JSON — de pipeline wordt een fractie van zijn huidige omvang)
- kan dezelfde output als named graphs in Fuseki zetten → k3_schema.nt
  vervalt als apart artefact (Termennetwerk/zpack lezen Fuseki of de
  gegenereerde nt rechtstreeks)
- property-mapping (P1→type, P24→ark, ...) als config i.p.v. query-code
Alternatief (meer wikibase-native, meer werk): een MediaWiki-extensie die
schone RDF per entity serveert — afgeraden: PHP-onderhoud, upgrade-last.

## Volgorde en afhankelijkheden

- Fase 0 kan nu (gesprek); URI-prefix-besluit van vrijdag MOET erin mee
- Fase 1 is onafhankelijk van 2 en 3 en lost het acute risico op
  (3 jaar oude containers, gebleken fragiele updater, één-persoons-kennis)
- Fase 2 en 3 kunnen samen: de nieuwe sync (2.3a) en de export (3) zijn
  dezelfde soort component — bouw ze als één klein programma
- Decommission-koppeling: na fase 2/3 kan fuseki op old-ingestion
  (k3-2/gebouwen-endpoint) vervangen worden door de nieuwe Fuseki —
  dat was fase 1 van het decommission-plan; deze migratie subsumeert dat

## Open punten

- [ ] arm64-compatibiliteit oude wikibase-images (fase 1 stap 2) testen
- [ ] MediaWiki 1.34→recent upgrade-pad met echte dump testen
- [ ] URI-prefix-besluit (vrijdag, Sjors) verwerken
- [ ] Wie wordt beheerder na overdracht (wij? monitoring/alerting regelen —
  de wdqs-storing bleef 2 maanden onopgemerkt; minimaal een
  freshness-check: max(dateModified) in store vs RecentChanges)
