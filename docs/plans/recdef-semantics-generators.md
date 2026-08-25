# Spec: recdef-semantiek-generatoren (RDFS, SHACL, JSON-LD context)

Status: goedgekeurd 25-08-2026. Vervolg op de XsdGenerator (sip-core, narthex 0.8.9.113)
en de ace-repo one-offs (`scripts/generate_rdfs.py` + `ace_ontology.yaml`).

## Doel

De record-definitie wordt de **enige bron van waarheid** voor structuur én semantiek.
Uit één geüploade recdef genereren we vier artefacten:

1. validation-XSD (bestaat al — `XsdGenerator`)
2. RDFS/OWL-ontologie
3. SHACL-shapes
4. JSON-LD `@context` + frame (drijft ook de linked-art-achtige preview in SIP-Creator)

`ace_ontology.yaml` en `scripts/generate_rdfs.py` in de ace-repo vervallen daarna.

## Besluiten (vragenronde 25-08)

| Vraag | Besluit |
|---|---|
| Bron semantiek | Alles in de recdef; YAML sterft |
| Runtime | Generatoren in sip-core; narthex genereert on-the-fly + download-links in UI |
| SHACL-scope | Spiegel van de lax-XSD; géén `sh:closed` |
| JSON-LD | Context én frame uit recdef; vervangt hardcoded `{"@type":"Thing"}` |
| sip.zip | **Ongewijzigd.** Regel: alles wat uit de recdef genereerbaar is wordt gegenereerd waar het gebruikt wordt, nooit meegeleverd — behalve XSD (oude desktops eisen fysiek bestand, `StorageImpl` "No validation XSD found") |

## 1. Recdef als enige bron — geen nieuwe elementen

Alle YAML-semantiek past in bestaande of nieuwe **inerte attributen**
(oude engines negeren onbekende attributen; bewezen met `xsdDataType` c.s.):

| Semantiek | Waar |
|---|---|
| Labels/definities (nl/en) | bestaand: `<doc><para name="Label/Definition" lang>` |
| subClassOf | bestaand attr `subclassof` — wordt nu ook echt gelezen |
| equivalentClass | **nieuw attr** `equivalentClass` op entiteit-elems |
| subPropertyOf | **nieuw attr** `subPropertyOf` op property-elems |
| range/datatype | afgeleid: `target` → object-range; `xsdDataType`/`uriCheck` → datatype |
| Ontology-URI, imports, versie | afgeleid: namespace van `prefix` = ontology-URI; overige namespaces = imports; recdef-versie |

Implementatie: twee nieuwe `@XStreamAsAttribute`-velden in `RecDef.Elem`
(**in Elem, niet Attr** — het eerste-anchor-incident van 25-08 niet herhalen).
Volledig backward-compatible.

## 2. sip-core: `RecDefSemantics` + drie emitters

Naast `XsdGenerator`, zelfde package `eu.delving.metadata`. RDF-emissie via
**Jena** (zit al in sip-core, zie `JenaHelper`) — geen nieuwe dependency.

- **`RecDefSemantics.from(recDef)`** — parset de **onresolvede** recdef één keer:
  entiteiten = root-children + templates, dedup op tag, **root wint** (zelfde regel
  als XsdGenerator); per entiteit de properties met annotaties
  (required/singular/xsdMin/Max/xsdDataType/xsdPattern/uriCheck/target) en
  doc-labels/definities per taal.
- **`RdfsGenerator`** → OWL/RDFS: `owl:Class` + `rdfs:label`/`rdfs:comment`
  (language-tagged) / `rdfs:subClassOf` / `owl:equivalentClass`;
  `owl:ObjectProperty` (heeft target) vs `owl:DatatypeProperty`;
  `rdfs:domain` = declarerende entiteiten, `rdfs:range` = target-klasse of xsd-type;
  `owl:Ontology`-kop met imports. Output: RDF/XML én Turtle.
- **`ShaclGenerator`** → Turtle: per entiteit `sh:NodeShape` + `sh:targetClass`;
  per property `sh:property` met `sh:path`, en constraints **alleen bij annotatie**:
  `sh:minCount`/`sh:maxCount` (required/singular/xsdMinOccurs/xsdMaxOccurs),
  `sh:datatype` (xsdDataType), `sh:pattern` (xsdPattern),
  `sh:class` + `sh:nodeKind sh:IRI` (target/uriCheck).
  Géén `sh:closed`: dual-declarations en extra `rdf:type`-triples zouden
  false positives geven.
- **`JsonLdContextGenerator`** → `@context`: prefixes uit de namespaces;
  per property een term `localName → URI`, met `"@type":"@id"` bij target/uriCheck,
  anders `"@type": xsdDataType` indien geannoteerd. Naam-botsing (zelfde localName,
  andere URI): eerste wint, rest blijft geprefixed.
  Plus **`generateFrame(recDef)`**: frame op de root-entiteit-types.

## 3. SIP-Creator-preview

`JenaHelper.convertRDF` krijgt een overload die een `@context` meeneemt
(`JsonLDWriteContext.setJsonLDContext`); `OutputFrame` voedt bij de
JSONLD-outputs de gegenereerde context + frame uit de actieve recdef, in plaats
van het hardcoded frame `{"@type":"Thing"}`. Resultaat: compacte leesbare keys,
genest per entiteit.

## 4. Narthex: on-the-fly endpoints + download-links

Geen opslag bij upload — genereren bij download uit de opgeslagen recdef
(generator verbetert → output altijd actueel, nooit stale bestanden):

```
GET /narthex/api/recdefs/:prefix/:version/ontology.rdf
GET /narthex/api/recdefs/:prefix/:version/ontology.ttl
GET /narthex/api/recdefs/:prefix/:version/shapes.ttl
GET /narthex/api/recdefs/:prefix/:version/context.jsonld
```

Recdefs-lijst-UI: drie download-linkjes per schema-versie (ontologie, shapes, context).

## 5. Ace-repo

`generate_rdfs.py` vervangen door een make-target dat de sip-core-jar aanroept.
Eerst **pariteitscheck**: Java-output vs python-output op ace 0.2.5.
`generate_docs.py`/mkdocs blijft ongemoeid. `ace_ontology.yaml`-inhoud
(equivalentClass, subPropertyOf, ontbrekende labels) migreert eenmalig als
attributen/doc-paras de recdef in.

## 6. Fasering + succescriteria

| Fase | Werk | Check |
|---|---|---|
| 1 | `RecDefSemantics` + `RdfsGenerator` + nieuwe attrs | pariteit met python-output op ace 0.2.5 |
| 2 | `ShaclGenerator` | shapes valideren clean tegen echte 0.2.5-output (Jena SHACL) |
| 3 | `JsonLdContextGenerator` + preview-wiring | preview adlib-thesau-record: compacte keys, genest |
| 4 | narthex endpoints + UI-links | drie downloads werken op datahub |

Niet in scope: sh:closed-handhaving, la-profiel-herschrijving van de output,
handhaving van shapes in hub3 (later), wijziging van sip.zip.
