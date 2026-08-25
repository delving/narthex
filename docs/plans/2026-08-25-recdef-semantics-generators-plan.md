# Recdef Semantics Generators Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate RDFS/OWL, SHACL shapes, and a JSON-LD context + frame from a record definition, in sip-core next to `XsdGenerator`, with narthex download endpoints and SIP-Creator preview wiring.

**Architecture:** One shared parser (`RecDefSemantics`) reads the unresolved `RecDef` object once; three thin emitters (`RdfsGenerator`, `ShaclGenerator`, `JsonLdContextGenerator`) produce artifacts from it. Narthex serves artifacts on-the-fly from stored recdefs (no files written at upload). SIP-Creator's JSON-LD preview feeds the generated context/frame into `JenaHelper` instead of the hardcoded `{"@type":"Thing"}` frame.

**Tech Stack:** Java 21 (sip-core, JUnit 5, Jena 3.17.0 Model API), Scala/Play (narthex), AngularJS 1.3 (narthex UI), python one-off migration in the ace repo.

**Spec:** `docs/plans/recdef-semantics-generators.md` (this repo). Read it first.

## Global Constraints

- Repos: sip-creator at `/home/kiivihal/code/java/sip-creator`, narthex at `/home/kiivihal/code/scala/narthex`, ace recdef repo at `/home/kiivihal/PocketMapper/recdef_repos/ace`.
- Narthex: build ONLY with `make compile` (never `sbt` directly). sip-creator: `mvn -q -pl sip-core test` etc.
- Narthex consumes sip-core as `"eu.delving" % "sip-core" % "1.4.1-SNAPSHOT"` — after sip-core changes run `mvn -q -pl sip-core -am install -DskipTests` in sip-creator so narthex picks the jar from `~/.m2`.
- Jena is pinned at **3.17.0** in BOTH sip-creator (`pom.xml` `<jena.version>`) and narthex (`build.sbt:63`). Use only the classic Model API (`org.apache.jena.rdf.model`) in generators — no newer riot/shacl main-scope APIs.
- New recdef attributes MUST be inert for old engines: `@XStreamAsAttribute` fields only (XStream ignores unknown attributes; proven with `xsdDataType`). New fields go in `RecDef.Elem` — NOT in `RecDef.Attr` (the Attr class appears first in the file; a previous edit landed there by mistake).
- Generators read the recdef's `root.subelements` + `templates` fields, which survive `resolve()` unchanged — same convention as `XsdGenerator` (dedup by tag, root declaration wins).
- Commit style: conventional commits, trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: Inert semantic attributes on RecDef.Elem + language on DocParagraph

**Files:**
- Modify: `sip-core/src/main/java/eu/delving/metadata/RecDef.java` (Elem class ~line 494-510; DocParagraph class ~line 344-351)
- Test: `sip-core/src/test/java/eu/delving/metadata/RecDefSemanticAttrsTest.java` (create)

**Interfaces:**
- Consumes: existing `RecDef.read(InputStream)`.
- Produces: `Elem.subclassof`, `Elem.equivalentClass`, `Elem.subPropertyOf` (all `String`, null when absent); `DocParagraph.lang` (`String`, null when absent). Later tasks read exactly these field names.

- [ ] **Step 1: Write the failing test**

```java
package eu.delving.metadata;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

public class RecDefSemanticAttrsTest {

    private static final String RECDEF = """
            <?xml version="1.0"?>
            <record-definition prefix="tst" version="0.0.1" flat="false">
                <namespaces>
                    <namespace prefix="tst" uri="http://example.org/tst#" schema="http://example.org/tst.xsd"/>
                    <namespace prefix="crm" uri="http://www.cidoc-crm.org/cidoc-crm/" schema="http://example.org/crm.xsd"/>
                </namespaces>
                <root tag="rdf:RDF">
                    <elem tag="crm:E22_Human-Made_Object" label="HumanMadeObject"
                          subclassof="crm:E19_Physical_Object"
                          equivalentClass="tst:HumanMadeObject">
                        <doc>
                            <para name="Label" lang="en">Human Made Object</para>
                            <para name="Definition" lang="nl">Doelbewust gemaakt fysiek object.</para>
                        </doc>
                        <elem tag="crm:P1_is_identified_by" subPropertyOf="crm:P1i"/>
                    </elem>
                </root>
            </record-definition>
            """;

    @Test
    public void semanticAttributesAreParsed() {
        RecDef recDef = RecDef.read(new ByteArrayInputStream(RECDEF.getBytes(StandardCharsets.UTF_8)));
        RecDef.Elem entity = recDef.root.subelements.get(0);
        assertEquals("crm:E19_Physical_Object", entity.subclassof);
        assertEquals("tst:HumanMadeObject", entity.equivalentClass);
        RecDef.Elem property = entity.subelements.get(0);
        assertEquals("crm:P1i", property.subPropertyOf);
    }

    @Test
    public void docParaLanguageIsParsed() {
        RecDef recDef = RecDef.read(new ByteArrayInputStream(RECDEF.getBytes(StandardCharsets.UTF_8)));
        RecDef.Elem entity = recDef.root.subelements.get(0);
        assertNotNull(entity.doc);
        RecDef.DocParagraph labelPara = entity.doc.paraList.get(0);
        assertEquals("Label", labelPara.name);
        assertEquals("en", labelPara.lang);
        assertEquals("Human Made Object", labelPara.content.trim());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/kiivihal/code/java/sip-creator && mvn -q -pl sip-core test -Dtest=RecDefSemanticAttrsTest`
Expected: COMPILE FAILURE — `subclassof`, `equivalentClass`, `subPropertyOf`, `lang` do not exist.

- [ ] **Step 3: Add the fields**

In `RecDef.java`, inside `public static class Elem` (the class declared at ~line 449 — verify with `grep -n "class Elem" RecDef.java`; do NOT touch class `Attr`), directly after the `xsdMaxOccurs` field:

```java
        // Semantic annotations for RDFS/SHACL/JSON-LD generation.
        // Ignored by the mapping engine and by older SIP-Creators
        // (XStream drops unknown attributes), like the xsd* group above.
        @XStreamAsAttribute
        @XStreamAlias("subclassof")
        public String subclassof;

        @XStreamAsAttribute
        @XStreamAlias("equivalentClass")
        public String equivalentClass;

        @XStreamAsAttribute
        @XStreamAlias("subPropertyOf")
        public String subPropertyOf;
```

In `public static class DocParagraph` add next to `name`:

```java
        @XStreamAsAttribute
        public String lang;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl sip-core test -Dtest=RecDefSemanticAttrsTest`
Expected: PASS (both tests).

- [ ] **Step 5: Run the whole sip-core suite (regression: attrs must stay inert)**

Run: `mvn -q -pl sip-core test`
Expected: all green, including `RecDefTemplateCycleTest` and `RecDefInlineDocTest`.

- [ ] **Step 6: Commit**

```bash
cd /home/kiivihal/code/java/sip-creator
git add sip-core/src/main/java/eu/delving/metadata/RecDef.java sip-core/src/test/java/eu/delving/metadata/RecDefSemanticAttrsTest.java
git commit -m "feat(sip-core): inert semantic attrs subclassof/equivalentClass/subPropertyOf + doc para lang"
```

---

### Task 2: RecDefSemantics shared model

**Files:**
- Create: `sip-core/src/main/java/eu/delving/metadata/RecDefSemantics.java`
- Test: `sip-core/src/test/java/eu/delving/metadata/RecDefSemanticsTest.java`

**Interfaces:**
- Consumes: `RecDef` fields (`prefix`, `version`, `namespaces`, `root.subelements`, `templates`) and Task 1's fields.
- Produces (used verbatim by Tasks 3, 5, 6):

```java
public class RecDefSemantics {
    public final String ontologyUri;               // namespace URI of recDef.prefix ("" if prefix undeclared)
    public final List<String> imports;             // other namespace URIs, recdef order, no rdf/xsd/xml
    public final String version;                   // recDef.version
    public final Map<String, String> namespaces;   // prefix -> uri (insertion order)
    public final Map<String, Entity> entities;     // key = entity tag, insertion order, root wins over template

    public static RecDefSemantics from(RecDef recDef);
    public String uriFor(String curie);            // "crm:E22_X" -> full URI; throws IllegalArgumentException on unknown prefix

    public static class Entity {
        public final String tag;
        public final Map<String, String> labels;       // lang -> text (from doc paras name="Label")
        public final Map<String, String> definitions;  // lang -> text (name="Definition")
        public final List<String> subClassOf;          // split of subclassof attr on ','
        public final String equivalentClass;           // may be null
        public final List<PropertyUse> properties;     // declared child elems, document order, dedup by tag
    }

    public static class PropertyUse {
        public final String tag;
        public final String target;        // comma-joined target attr, null = datatype property
        public final String dataType;      // xsdDataType, or "xsd:anyURI" when uriCheck and no xsdDataType, else null
        public final String pattern;       // xsdPattern or null
        public final String minOccurs;     // xsdMinOccurs, else "1" if required, else "0"
        public final String maxOccurs;     // xsdMaxOccurs, else "1" if singular, else null (= unbounded)
        public final boolean uriCheck;
        public final String subPropertyOf; // may be null
        public final Map<String, String> labels;
        public final Map<String, String> definitions;
    }
}
```

Rules `from()` must implement:
- Entities = `recDef.templates` first, then `recDef.root.subelements` overwriting on same tag (root wins) — the `XsdGenerator.generate` collection loop (`XsdGenerator.java:50-61`) is the reference; skip elems with null tag or empty local name.
- An entity's `properties` come from its direct `subelements`; skip null tags; first occurrence of a tag wins.
- Labels/definitions from the elem's own `doc.paraList` (also check the legacy `doc.paras` list when `paraList` is null): paras with `name="Label"` / `name="Definition"` and non-null `lang`; skip paras without lang.
- `uriFor` resolves via `namespaces`; a bare `http(s)://...` string passes through unchanged.

- [ ] **Step 1: Write the failing test**

```java
package eu.delving.metadata;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

public class RecDefSemanticsTest {

    static final String RECDEF = """
            <?xml version="1.0"?>
            <record-definition prefix="tst" version="0.0.1" flat="false">
                <namespaces>
                    <namespace prefix="tst" uri="http://example.org/tst#" schema="s"/>
                    <namespace prefix="crm" uri="http://www.cidoc-crm.org/cidoc-crm/" schema="s"/>
                    <namespace prefix="dc" uri="http://purl.org/dc/elements/1.1/" schema="s"/>
                </namespaces>
                <root tag="rdf:RDF">
                    <elem tag="crm:E22_Human-Made_Object" label="HumanMadeObject"
                          subclassof="crm:E19_Physical_Object" equivalentClass="tst:HumanMadeObject">
                        <doc>
                            <para name="Label" lang="en">Human Made Object</para>
                            <para name="Definition" lang="nl">Doelbewust gemaakt object.</para>
                        </doc>
                        <elem tag="crm:P1_is_identified_by" target="crm:E41_Appellation"/>
                        <elem tag="dc:date" xsdDataType="xsd:date" required="true"/>
                        <elem tag="dc:identifier" uriCheck="true" singular="true"/>
                    </elem>
                </root>
                <templates>
                    <elem tag="crm:E41_Appellation" label="Appellation">
                        <elem tag="dc:title"/>
                    </elem>
                    <elem tag="crm:E22_Human-Made_Object" label="ShadowedByRoot"/>
                </templates>
            </record-definition>
            """;

    private RecDefSemantics semantics() {
        return RecDefSemantics.from(RecDef.read(
            new ByteArrayInputStream(RECDEF.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void ontologyHeaderDerivedFromNamespaces() {
        RecDefSemantics s = semantics();
        assertEquals("http://example.org/tst#", s.ontologyUri);
        assertEquals("0.0.1", s.version);
        assertEquals(java.util.List.of(
            "http://www.cidoc-crm.org/cidoc-crm/",
            "http://purl.org/dc/elements/1.1/"), s.imports);
    }

    @Test
    public void rootDeclarationWinsOverTemplate() {
        RecDefSemantics.Entity e = semantics().entities.get("crm:E22_Human-Made_Object");
        assertEquals(3, e.properties.size()); // the root shape, not the empty template shadow
        assertEquals("crm:E19_Physical_Object", e.subClassOf.get(0));
        assertEquals("tst:HumanMadeObject", e.equivalentClass);
        assertEquals("Human Made Object", e.labels.get("en"));
        assertEquals("Doelbewust gemaakt object.", e.definitions.get("nl"));
    }

    @Test
    public void propertyAnnotationsResolve() {
        RecDefSemantics.Entity e = semantics().entities.get("crm:E22_Human-Made_Object");
        RecDefSemantics.PropertyUse p1 = e.properties.get(0);
        assertEquals("crm:E41_Appellation", p1.target);
        assertNull(p1.dataType);
        RecDefSemantics.PropertyUse date = e.properties.get(1);
        assertEquals("xsd:date", date.dataType);
        assertEquals("1", date.minOccurs);
        assertNull(date.maxOccurs);
        RecDefSemantics.PropertyUse id = e.properties.get(2);
        assertEquals("xsd:anyURI", id.dataType);
        assertEquals("1", id.maxOccurs);
        assertEquals("0", id.minOccurs);
    }

    @Test
    public void uriForResolvesCuriesAndRejectsUnknownPrefix() {
        RecDefSemantics s = semantics();
        assertEquals("http://www.cidoc-crm.org/cidoc-crm/E22_Human-Made_Object",
            s.uriFor("crm:E22_Human-Made_Object"));
        assertEquals("http://example.org/full", s.uriFor("http://example.org/full"));
        assertThrows(IllegalArgumentException.class, () -> s.uriFor("nope:X"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl sip-core test -Dtest=RecDefSemanticsTest`
Expected: COMPILE FAILURE — `RecDefSemantics` does not exist.

- [ ] **Step 3: Implement `RecDefSemantics`**

Create the class exactly per the Interfaces block above. Implementation notes:
- Same header/license comment style as `XsdGenerator.java`.
- `xsd` namespace: always resolvable — seed `namespaces` with `xsd -> http://www.w3.org/2001/XMLSchema#` and `rdf -> http://www.w3.org/1999/02/22-rdf-syntax-ns#` before adding the recdef's declared ones (declared wins on collision). Exclude `rdf`, `xsd`, `xml`, and the recdef's own prefix from `imports`.
- Doc-para extraction as a private static helper `Map<String,String>[] docMaps(RecDef.Elem elem)` or two helpers `labelsOf`/`definitionsOf` — keep it plain, no streams-heavy cleverness.
- All collections wrapped `Collections.unmodifiable*`; use `LinkedHashMap`/`ArrayList` internally.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl sip-core test -Dtest=RecDefSemanticsTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add sip-core/src/main/java/eu/delving/metadata/RecDefSemantics.java sip-core/src/test/java/eu/delving/metadata/RecDefSemanticsTest.java
git commit -m "feat(sip-core): RecDefSemantics — shared semantic model parsed from unresolved recdef"
```

---

### Task 3: RdfsGenerator

**Files:**
- Create: `sip-core/src/main/java/eu/delving/metadata/RdfsGenerator.java`
- Test: `sip-core/src/test/java/eu/delving/metadata/RdfsGeneratorTest.java`

**Interfaces:**
- Consumes: `RecDefSemantics.from(recDef)` and its fields (Task 2).
- Produces: `public static String generate(RecDef recDef, String jenaLang)` where `jenaLang` is `"RDF/XML-ABBREV"` or `"TURTLE"` (passed straight to `Model.write`). Tasks 8 uses this signature.

Mapping rules:
- `owl:Ontology` node at `ontologyUri` with `owl:imports` for each import and `owl:versionInfo` = version.
- Per entity: `rdf:type owl:Class`, `rdfs:label` (language-tagged, per labels entry), `rdfs:comment` (per definitions entry), `rdfs:subClassOf` per subClassOf CURIE, `owl:equivalentClass` when set. `rdfs:isDefinedBy` -> ontology node.
- Properties merged ACROSS entities by tag: `rdf:type owl:ObjectProperty` when any use has a target, else `owl:DatatypeProperty`; `rdfs:domain` per declaring entity; `rdfs:range` = union of target classes (one triple per distinct target) or the XSD datatype URI when dataType set; `rdfs:subPropertyOf` when set; labels/comments from the first use that has them.
- Skip (do not throw) any CURIE whose prefix `uriFor` cannot resolve at class/property REFERENCE positions (subclassof to an external unlisted vocab must not kill generation) — but log nothing, just skip that single triple; entity/property SUBJECT tags failing to resolve throw `IllegalArgumentException` (that recdef is broken).
- Register all `semantics.namespaces` as model prefixes (`model.setNsPrefix`).

- [ ] **Step 1: Write the failing test** — reuse `RecDefSemanticsTest.RECDEF` (make that constant package-visible `static final` as written) and assert on a parsed model:

```java
package eu.delving.metadata;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

public class RdfsGeneratorTest {

    private Model generateModel() {
        RecDef recDef = RecDef.read(new ByteArrayInputStream(
            RecDefSemanticsTest.RECDEF.getBytes(StandardCharsets.UTF_8)));
        String rdfXml = RdfsGenerator.generate(recDef, "RDF/XML-ABBREV");
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(rdfXml), null, "RDF/XML");
        return model;
    }

    @Test
    public void classesWithLabelsAndHierarchy() {
        Model m = generateModel();
        Resource e22 = m.getResource("http://www.cidoc-crm.org/cidoc-crm/E22_Human-Made_Object");
        assertTrue(m.contains(e22, RDF.type, OWL.Class));
        assertTrue(m.contains(e22, RDFS.subClassOf,
            m.getResource("http://www.cidoc-crm.org/cidoc-crm/E19_Physical_Object")));
        assertTrue(m.contains(e22, OWL.equivalentClass,
            m.getResource("http://example.org/tst#HumanMadeObject")));
        assertEquals("Human Made Object",
            e22.getProperty(RDFS.label, "en").getString());
    }

    @Test
    public void objectAndDatatypePropertiesWithDomainRange() {
        Model m = generateModel();
        Resource p1 = m.getResource("http://www.cidoc-crm.org/cidoc-crm/P1_is_identified_by");
        assertTrue(m.contains(p1, RDF.type, OWL.ObjectProperty));
        assertTrue(m.contains(p1, RDFS.domain,
            m.getResource("http://www.cidoc-crm.org/cidoc-crm/E22_Human-Made_Object")));
        assertTrue(m.contains(p1, RDFS.range,
            m.getResource("http://www.cidoc-crm.org/cidoc-crm/E41_Appellation")));
        Resource date = m.getResource("http://purl.org/dc/elements/1.1/date");
        assertTrue(m.contains(date, RDF.type, OWL.DatatypeProperty));
        assertTrue(m.contains(date, RDFS.range,
            m.getResource("http://www.w3.org/2001/XMLSchema#date")));
    }

    @Test
    public void ontologyHeader() {
        Model m = generateModel();
        Resource ont = m.getResource("http://example.org/tst#");
        assertTrue(m.contains(ont, RDF.type, OWL.Ontology));
        assertTrue(m.contains(ont, OWL.imports,
            m.getResource("http://www.cidoc-crm.org/cidoc-crm/")));
    }

    @Test
    public void turtleOutputParses() {
        RecDef recDef = RecDef.read(new ByteArrayInputStream(
            RecDefSemanticsTest.RECDEF.getBytes(StandardCharsets.UTF_8)));
        String ttl = RdfsGenerator.generate(recDef, "TURTLE");
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(ttl), null, "TURTLE");
        assertFalse(model.isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl sip-core test -Dtest=RdfsGeneratorTest`
Expected: COMPILE FAILURE — `RdfsGenerator` does not exist.

- [ ] **Step 3: Implement `RdfsGenerator`** per the mapping rules in the Interfaces block. Build a `Model`, add triples, serialize with `StringWriter` + `model.write(writer, jenaLang)`. XSD datatype CURIEs (`xsd:date`) resolve through `uriFor` like everything else.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl sip-core test -Dtest=RdfsGeneratorTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add sip-core/src/main/java/eu/delving/metadata/RdfsGenerator.java sip-core/src/test/java/eu/delving/metadata/RdfsGeneratorTest.java
git commit -m "feat(sip-core): RdfsGenerator — OWL/RDFS ontology from recdef semantics"
```

---

### Task 4: Ace 0.2.5 — YAML migration + parity check against the python generator

**Files:**
- Create: `/home/kiivihal/PocketMapper/recdef_repos/ace/scripts/migrate_ontology_yaml.py` (one-off, stays in repo for the record)
- Modify: `/home/kiivihal/PocketMapper/recdef_repos/ace/latest/ace_record-definition.xml` (on a NEW branch `semantics-in-recdef`, based on `fix/dual-declaration-templates`)
- Scratch: `/tmp/claude-1000/-home-kiivihal-code-scala-narthex/d1b37e6c-4504-4fda-8559-449862ab7acc/scratchpad/parity/`

**Interfaces:**
- Consumes: `latest/ace_ontology.yaml` (keys per class/property: `label`, `definition`, `subClassOf`, `equivalentClass`, `subPropertyOf`, `range`), `latest/ace_record-definition.xml`, `RdfsGenerator` (Task 3).
- Produces: a recdef carrying all YAML semantics as attributes/doc-paras; a parity verdict.

- [ ] **Step 1: Create the branch**

```bash
cd /home/kiivihal/PocketMapper/recdef_repos/ace
git checkout fix/dual-declaration-templates
git checkout -b semantics-in-recdef
```

- [ ] **Step 2: Write the migration script**

`scripts/migrate_ontology_yaml.py`: parse `ace_ontology.yaml` with PyYAML and `ace_record-definition.xml` with `xml.etree` preserving order (reuse the parsing helpers from `generate_rdfs.py` — same tag-matching logic). For each recdef `<elem>` whose tag matches a YAML class/property entry:
- YAML `subClassOf` -> ensure `subclassof` attr (merge, comma-separated, keep existing).
- YAML `equivalentClass` -> `equivalentClass` attr.
- YAML `subPropertyOf` -> `subPropertyOf` attr.
- YAML `label`/`definition` per language -> ensure `<doc><para name="Label|Definition" lang="..">` exists (only add missing languages, never overwrite existing paras).
- YAML `range` on datatype properties -> `xsdDataType` attr when the elem has no target and no xsdDataType yet.
Write back with minimal diff (ElementTree rewrite is acceptable; check the diff manually). Print a summary: N attrs added, M paras added, unmatched YAML keys listed.

- [ ] **Step 3: Run migration + eyeball the diff**

```bash
python3 scripts/migrate_ontology_yaml.py latest/ace_ontology.yaml latest/ace_record-definition.xml
git diff --stat latest/ace_record-definition.xml
```
Expected: attrs/paras added; unmatched list should be empty or explainable (report it).

- [ ] **Step 4: Verify the migrated recdef still resolves on BOTH engines**

Same harness as before (test tools live on datahub `/tmp`):

```bash
scp latest/ace_record-definition.xml root@datahub.acpt.delving.io:/tmp/sem_test.xml
ssh root@datahub.acpt.delving.io 'CPX=$(ls /opt/hub3/datahub/NarthexVersions/current/lib/*.jar | grep -v sip-core | tr "\n" ":"); CPO=$(ls /opt/hub3/datahub/NarthexVersions/current/lib/*.jar | tr "\n" ":"); java -cp "/tmp/sip-core-test.jar:$CPX:/tmp" RecDefTest /tmp/sem_test.xml; java -cp "$CPO:/tmp" RecDefTest /tmp/sem_test.xml'
```
Expected: `RESOLVED OK` twice. (Old engine ignores the new attributes — this is the proof.)

- [ ] **Step 5: Generate both ontologies and compare**

```bash
SCRATCH=/tmp/claude-1000/-home-kiivihal-code-scala-narthex/d1b37e6c-4504-4fda-8559-449862ab7acc/scratchpad/parity
mkdir -p $SCRATCH
python3 scripts/generate_rdfs.py latest/ace_ontology.yaml -o $SCRATCH/python.rdf
# Java side: small runner against the sip-core jar
cd /home/kiivihal/code/java/sip-creator && mvn -q -pl sip-core -am install -DskipTests
```
Write `$SCRATCH/RdfsRun.java` (`public static void main`: `RecDef.read` the file, print `RdfsGenerator.generate(recDef, "RDF/XML-ABBREV")`), compile against `~/.m2/repository/eu/delving/sip-core/1.4.1-SNAPSHOT/sip-core-1.4.1-SNAPSHOT.jar` plus its XStream/Jena deps (copy classpath via `mvn -q -pl sip-core dependency:build-classpath -Dmdep.outputFile=$SCRATCH/cp.txt`), run it on the migrated recdef into `$SCRATCH/java.rdf`. Then compare as GRAPHS, not text — python one-liner with rdflib:

```bash
python3 - <<'EOF'
import rdflib
from rdflib.compare import graph_diff, to_isomorphic
g1 = to_isomorphic(rdflib.Graph().parse("PYTHON.rdf"))
g2 = to_isomorphic(rdflib.Graph().parse("JAVA.rdf"))
common, only_py, only_java = graph_diff(g1, g2)
print("only in python:", len(only_py), "only in java:", len(only_java))
for t in sorted(only_py)[:40]: print("PY ", t)
for t in sorted(only_java)[:40]: print("JV ", t)
EOF
```
(substitute the real paths). Expected: differences only in known categories — python emits YAML-ontology extras the recdef legitimately lacks (e.g. ontology label) and java emits `rdfs:isDefinedBy`. Investigate anything else; fix generator or migration until the diff is explainable, and record the explanation in the task report.

- [ ] **Step 6: Commit the branch**

```bash
cd /home/kiivihal/PocketMapper/recdef_repos/ace
git add scripts/migrate_ontology_yaml.py latest/ace_record-definition.xml
git commit -m "feat: migrate ontology YAML semantics into the record definition

The recdef is now the single source of truth: subclassof/equivalentClass/
subPropertyOf attributes and Label/Definition doc-paras carry what
ace_ontology.yaml held. Parity-checked against generate_rdfs.py output.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
Do NOT push yet — this stacks on the unreviewed PR #7; push after Bert merges or on explicit request.

---

### Task 5: ShaclGenerator

**Files:**
- Create: `sip-core/src/main/java/eu/delving/metadata/ShaclGenerator.java`
- Modify: `sip-core/pom.xml` (add `jena-shacl` 3.17.0, **test scope only**)
- Test: `sip-core/src/test/java/eu/delving/metadata/ShaclGeneratorTest.java`

**Interfaces:**
- Consumes: `RecDefSemantics` (Task 2).
- Produces: `public static String generate(RecDef recDef)` returning Turtle. Task 8 uses this signature.

Mapping rules (mirror of the lax XSD; constraints only where annotated):
- Per entity: `<ontologyUri>shapes/<localName>Shape` `a sh:NodeShape ; sh:targetClass <entityUri>`.
- Per PropertyUse a `sh:property` blank node with `sh:path <propertyUri>` and ONLY:
  - `sh:minCount` when minOccurs != "0"; `sh:maxCount` when maxOccurs != null;
  - `sh:datatype` when dataType set and not `xsd:anyURI`-via-uriCheck;
  - `sh:nodeKind sh:IRI` when uriCheck or target set;
  - `sh:class <targetUri>` per resolvable target;
  - `sh:pattern` when pattern set.
- NO `sh:closed` (spec: dual declarations + extra rdf:type triples would false-positive).
- SHACL vocabulary: jena-arq 3.17 has no SH vocabulary class — declare the needed `Property`/`Resource` constants privately from `http://www.w3.org/ns/shacl#`.

- [ ] **Step 1: Add test-scope dependency** in `sip-core/pom.xml` next to the existing jena-arq dependency:

```xml
        <dependency>
            <groupId>org.apache.jena</groupId>
            <artifactId>jena-shacl</artifactId>
            <version>${jena.version}</version>
            <scope>test</scope>
        </dependency>
```
(Root pom defines `<jena.version>3.17.0</jena.version>`; if the property is not inherited into sip-core/pom.xml's dependency section, write `3.17.0` literally.)

- [ ] **Step 2: Write the failing test**

```java
package eu.delving.metadata;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

public class ShaclGeneratorTest {

    private String shapesTtl() {
        RecDef recDef = RecDef.read(new ByteArrayInputStream(
            RecDefSemanticsTest.RECDEF.getBytes(StandardCharsets.UTF_8)));
        return ShaclGenerator.generate(recDef);
    }

    private ValidationReport validate(String dataTtl) {
        Model shapesModel = ModelFactory.createDefaultModel();
        shapesModel.read(new StringReader(shapesTtl()), null, "TURTLE");
        Model data = ModelFactory.createDefaultModel();
        data.read(new StringReader(dataTtl), null, "TURTLE");
        return ShaclValidator.get().validate(
            Shapes.parse(shapesModel.getGraph()), data.getGraph());
    }

    // Recdef under test: dc:date required + xsd:date; dc:identifier singular + anyURI/IRI;
    // crm:P1_is_identified_by -> class crm:E41_Appellation.

    @Test
    public void conformingRecordPasses() {
        ValidationReport report = validate("""
            @prefix crm: <http://www.cidoc-crm.org/cidoc-crm/> .
            @prefix dc: <http://purl.org/dc/elements/1.1/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            <http://x/obj> a crm:E22_Human-Made_Object ;
                dc:date "2020-01-01"^^xsd:date ;
                dc:identifier <http://x/id/1> ;
                crm:P1_is_identified_by <http://x/app> .
            <http://x/app> a crm:E41_Appellation .
            """);
        assertTrue(report.conforms(), report.toString());
    }

    @Test
    public void missingRequiredDateFails() {
        ValidationReport report = validate("""
            @prefix crm: <http://www.cidoc-crm.org/cidoc-crm/> .
            <http://x/obj> a crm:E22_Human-Made_Object .
            """);
        assertFalse(report.conforms());
    }

    @Test
    public void doubleSingularIdentifierFails() {
        ValidationReport report = validate("""
            @prefix crm: <http://www.cidoc-crm.org/cidoc-crm/> .
            @prefix dc: <http://purl.org/dc/elements/1.1/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            <http://x/obj> a crm:E22_Human-Made_Object ;
                dc:date "2020-01-01"^^xsd:date ;
                dc:identifier <http://x/id/1> , <http://x/id/2> .
            """);
        assertFalse(report.conforms());
    }

    @Test
    public void undeclaredPropertyIsAllowedBecauseNotClosed() {
        ValidationReport report = validate("""
            @prefix crm: <http://www.cidoc-crm.org/cidoc-crm/> .
            @prefix dc: <http://purl.org/dc/elements/1.1/> .
            @prefix dcterms: <http://purl.org/dc/terms/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            <http://x/obj> a crm:E22_Human-Made_Object ;
                dc:date "2020-01-01"^^xsd:date ;
                dcterms:extent "10 cm" .
            """);
        assertTrue(report.conforms(), report.toString());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q -pl sip-core test -Dtest=ShaclGeneratorTest`
Expected: COMPILE FAILURE — `ShaclGenerator` does not exist.

- [ ] **Step 4: Implement `ShaclGenerator`** per the mapping rules. Build a Jena Model, blank nodes via `model.createResource()` (no URI), serialize `TURTLE`.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -pl sip-core test -Dtest=ShaclGeneratorTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Real-world check — shapes vs actual ace output**

Rebuild jar (`mvn -q -pl sip-core -am install -DskipTests`), generate shapes from the migrated ace recdef (Task 4 branch), and validate ONE real processed record from datahub adlib-thesau against them with a small runner using `ShaclValidator` (test-scope dep is on the test classpath: run via `mvn -q -pl sip-core test-compile exec:java` or a scratch-compiled runner with jena-shacl jar from `~/.m2`). Expected: conforms, or violations that are genuine data findings (report them, don't silence them).

- [ ] **Step 7: Commit**

```bash
git add sip-core/pom.xml sip-core/src/main/java/eu/delving/metadata/ShaclGenerator.java sip-core/src/test/java/eu/delving/metadata/ShaclGeneratorTest.java
git commit -m "feat(sip-core): ShaclGenerator — annotation-mirroring shapes, no sh:closed"
```

---

### Task 6: JsonLdContextGenerator (+ frame)

**Files:**
- Create: `sip-core/src/main/java/eu/delving/metadata/JsonLdContextGenerator.java`
- Test: `sip-core/src/test/java/eu/delving/metadata/JsonLdContextGeneratorTest.java`

**Interfaces:**
- Consumes: `RecDefSemantics` (Task 2).
- Produces (Task 7 and Task 8 use these signatures):

```java
public static String generateContext(RecDef recDef); // full {"@context":{...}} JSON document
public static String generateFrame(RecDef recDef);   // {"@context":{...},"@type":[...entity URIs...]} JSON
```

Context rules:
- Every namespace prefix -> uri entry.
- Per property (merged across entities by tag, first wins): term = localName; value `{"@id": "<full URI>"}` plus `"@type":"@id"` when target or uriCheck, else `"@type":"<datatype URI>"` when dataType set; plain string URI shorthand when neither.
- Term collision (same localName, different URI): FIRST wins; later ones get no term (stay prefixed in output). Entities also get terms (localName -> URI) — same collision rule, properties claim first.
- Frame: `"@type"` = JSON array of all ROOT entity URIs (entities that came from `root.subelements`, not template-only ones) — RecDefSemantics needs to expose that distinction: add `public final boolean fromRoot;` to `Entity` in Task 2's class (set true when the tag was (re)declared under root). If Task 2 is already committed, add the field now with a covering assertion in `RecDefSemanticsTest`.
- Emit JSON with Gson (`com.google.gson` — already a sip-core dependency, see `JenaHelper`), pretty-printed.

- [ ] **Step 1: Write the failing test**

```java
package eu.delving.metadata;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

public class JsonLdContextGeneratorTest {

    private RecDef recDef() {
        return RecDef.read(new ByteArrayInputStream(
            RecDefSemanticsTest.RECDEF.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void contextHasPrefixesAndTypedTerms() {
        JsonObject ctx = JsonParser.parseString(JsonLdContextGenerator.generateContext(recDef()))
            .getAsJsonObject().getAsJsonObject("@context");
        assertEquals("http://www.cidoc-crm.org/cidoc-crm/", ctx.get("crm").getAsString());
        JsonObject p1 = ctx.getAsJsonObject("P1_is_identified_by");
        assertEquals("http://www.cidoc-crm.org/cidoc-crm/P1_is_identified_by", p1.get("@id").getAsString());
        assertEquals("@id", p1.get("@type").getAsString());
        JsonObject date = ctx.getAsJsonObject("date");
        assertEquals("http://www.w3.org/2001/XMLSchema#date", date.get("@type").getAsString());
    }

    @Test
    public void frameTargetsRootEntitiesOnly() {
        JsonObject frame = JsonParser.parseString(JsonLdContextGenerator.generateFrame(recDef()))
            .getAsJsonObject();
        assertTrue(frame.has("@context"));
        var types = frame.getAsJsonArray("@type");
        assertEquals(1, types.size()); // E22 from root; E41 is template-only
        assertEquals("http://www.cidoc-crm.org/cidoc-crm/E22_Human-Made_Object",
            types.get(0).getAsString());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl sip-core test -Dtest=JsonLdContextGeneratorTest`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement** — add `Entity.fromRoot` to `RecDefSemantics` (+ one assertion in `RecDefSemanticsTest.rootDeclarationWinsOverTemplate`: `assertTrue(e.fromRoot)` and for `crm:E41_Appellation` `assertFalse`), then `JsonLdContextGenerator` per rules.

- [ ] **Step 4: Run tests**

Run: `mvn -q -pl sip-core test -Dtest='JsonLdContextGeneratorTest,RecDefSemanticsTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sip-core/src/main/java/eu/delving/metadata/JsonLdContextGenerator.java sip-core/src/test/java/eu/delving/metadata/JsonLdContextGeneratorTest.java sip-core/src/main/java/eu/delving/metadata/RecDefSemantics.java sip-core/src/test/java/eu/delving/metadata/RecDefSemanticsTest.java
git commit -m "feat(sip-core): JsonLdContextGenerator — @context and frame from recdef"
```

---

### Task 7: SIP-Creator preview wiring (context + frame instead of {"@type":"Thing"})

**Files:**
- Modify: `sip-core/src/main/java/eu/delving/metadata/JenaHelper.java`
- Modify: `sip-app/src/main/java/eu/delving/sip/model/MappingCompileModel.java:491`
- Test: extend `sip-core/src/test/java/eu/delving/metadata/JenaHelperTest.java`

**Interfaces:**
- Consumes: `JsonLdContextGenerator.generateContext/generateFrame` (Task 6).
- Produces: `public static String convertRDF(String defaultPrefix, String rdf, RDFFormat outputFormat, String contextJson, String frameJson)` in `JenaHelper` — contextJson used for `JSONLD_COMPACT_PRETTY` via `JsonLDWriteContext.setJsonLDContext(String)`, frameJson for `JSONLD_FRAME_PRETTY` via `setFrame(String)`; either may be null -> fall back to existing behavior.

- [ ] **Step 1: Write the failing test** — add to `JenaHelperTest`:

```java
    @Test
    public void compactWithGeneratedContextUsesShortTerms() {
        String context = """
            {"@context": {"ex": "http://example.org/",
                          "name": {"@id": "http://example.org/name"}}}
            """;
        String compact = JenaHelper.convertRDF("ex", SAMPLE_RDF,
            RDFFormat.JSONLD_COMPACT_PRETTY, context, null);
        assertTrue(compact.contains("\"name\""), compact);
        assertFalse(compact.contains("http://example.org/name"), compact);
    }

    @Test
    public void frameStringDrivesFraming() {
        String frame = """
            {"@context": {"ex": "http://example.org/"},
             "@type": "http://example.org/Thing"}
            """;
        String framed = JenaHelper.convertRDF("ex", SAMPLE_RDF,
            RDFFormat.JSONLD_FRAME_PRETTY, null, frame);
        assertTrue(framed.contains("item1"), framed);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl sip-core test -Dtest=JenaHelperTest`
Expected: COMPILE FAILURE — no 5-arg overload.

- [ ] **Step 3: Implement the overload** in `JenaHelper`: same body pattern as the existing private `convertRDFWithFrame` (`JenaHelper.java:83-98`) but set `ctx.setJsonLDContext(contextJson)` when non-null and `ctx.setFrame(frameJson)` when non-null; for `JSONLD_COMPACT_PRETTY` write through `RDFDataMgr.createGraphWriter` with the ctx (the existing `convertRDFTo` path has no ctx hook). Null context AND null frame -> delegate to the existing overloads unchanged.

- [ ] **Step 4: Wire the preview.** In `MappingCompileModel.java` around line 491 replace:

```java
                    output = JenaHelper.convertRDF(recMapping.getDefaultPrefix(), output, rdfFormat);
```

with:

```java
                    if (rdfFormat == RDFFormat.JSONLD_COMPACT_PRETTY || rdfFormat == RDFFormat.JSONLD_FRAME_PRETTY) {
                        RecDef recDef = recMapping.getRecDefTree().getRecDef();
                        String contextJson = JsonLdContextGenerator.generateContext(recDef);
                        String frameJson = rdfFormat == RDFFormat.JSONLD_FRAME_PRETTY
                                ? JsonLdContextGenerator.generateFrame(recDef) : null;
                        output = JenaHelper.convertRDF(recMapping.getDefaultPrefix(), output, rdfFormat, contextJson, frameJson);
                    } else {
                        output = JenaHelper.convertRDF(recMapping.getDefaultPrefix(), output, rdfFormat);
                    }
```

Guard: wrap the generator calls in try/catch and fall back to the old call on any exception — a recdef defect must never blank the preview. (Generation runs per preview record; it is string-building over an in-memory object, cheap enough not to cache.)

- [ ] **Step 5: Run tests + full build**

Run: `mvn -q -pl sip-core test -Dtest=JenaHelperTest` then `mvn -q -pl sip-core,sip-app -am install -DskipTests`
Expected: tests PASS, build green.

- [ ] **Step 6: Commit**

```bash
git add sip-core/src/main/java/eu/delving/metadata/JenaHelper.java sip-core/src/test/java/eu/delving/metadata/JenaHelperTest.java sip-app/src/main/java/eu/delving/sip/model/MappingCompileModel.java
git commit -m "feat(sip): JSON-LD preview uses generated context and frame from the recdef"
```

---

### Task 8: Narthex on-the-fly artifact endpoint

**Files:**
- Modify: `/home/kiivihal/code/scala/narthex/conf/routes` (after line 100, the xsd route)
- Modify: `/home/kiivihal/code/scala/narthex/app/controllers/AppController.scala` (next to `getRecDefXsd`, ~line 1284)

**Interfaces:**
- Consumes: `RecDefRepo.getVersion(prefix, hash): Option[RecDefVersionResolved]` (field `recordDefinitionFile: File`); sip-core generators (Tasks 3, 5, 6) from the refreshed `1.4.1-SNAPSHOT` jar (`mvn -q -pl sip-core -am install -DskipTests` first).
- Produces: `GET /narthex/app/rec-defs/:prefix/:hash/artifact/:name` with `name` in `ontology.rdf`, `ontology.ttl`, `shapes.ttl`, `context.jsonld`. Task 9 links to it.

- [ ] **Step 1: Add the route** after the xsd route in `conf/routes`:

```
GET         /narthex/app/rec-defs/:prefix/:hash/artifact/:name               controllers.AppController.getRecDefArtifact(prefix, hash, name)
```

- [ ] **Step 2: Add the action** in `AppController.scala` directly after `getRecDefXsd`:

```scala
  /** Semantic artifacts generated on the fly from the stored recdef —
    * never stored, so a generator improvement is immediately visible. */
  def getRecDefArtifact(prefix: String, hash: String, name: String) = Action { request =>
    recDefRepo.getVersion(prefix, hash) match {
      case Some(resolved) =>
        try {
          val recDef = eu.delving.metadata.RecDef.read(
            new java.io.FileInputStream(resolved.recordDefinitionFile))
          name match {
            case "ontology.rdf" =>
              Ok(eu.delving.metadata.RdfsGenerator.generate(recDef, "RDF/XML-ABBREV")).as("application/rdf+xml")
            case "ontology.ttl" =>
              Ok(eu.delving.metadata.RdfsGenerator.generate(recDef, "TURTLE")).as("text/turtle")
            case "shapes.ttl" =>
              Ok(eu.delving.metadata.ShaclGenerator.generate(recDef)).as("text/turtle")
            case "context.jsonld" =>
              Ok(eu.delving.metadata.JsonLdContextGenerator.generateContext(recDef)).as("application/ld+json")
            case _ =>
              NotFound(Json.obj("problem" -> s"Unknown artifact: $name"))
          }
        } catch {
          case e: Exception =>
            InternalServerError(Json.obj("problem" -> s"Generation failed: ${e.getMessage}"))
        }
      case None =>
        NotFound(Json.obj("problem" -> s"Rec-def not found: $prefix/$hash"))
    }
  }
```

- [ ] **Step 3: Compile**

Run: `cd /home/kiivihal/code/scala/narthex && make compile`
Expected: green. If `RdfsGenerator` is unresolved, the local sip-core jar was not refreshed — rerun the maven install from Global Constraints.

- [ ] **Step 4: Smoke locally**

Start `sbt run` is NOT needed; instead verify via the dev instance if running, else defer to the datahub deploy check (Task 10 of the rollout). Minimum here: `make compile` green + routes file parses (compile covers it).

- [ ] **Step 5: Commit**

```bash
cd /home/kiivihal/code/scala/narthex
git add conf/routes app/controllers/AppController.scala
git commit -m "feat(recdefs): on-the-fly RDFS/SHACL/JSON-LD-context downloads per schema version"
```

---

### Task 9: Narthex UI download links

**Files:**
- Modify: `/home/kiivihal/code/scala/narthex/app/assets/javascripts/recdefs/recdefs-controllers.js` (next to `downloadXmlUrl`, line ~107)
- Modify: `/home/kiivihal/code/scala/narthex/public/templates/recdefs-list.html` (version row, lines ~116-121)

**Interfaces:**
- Consumes: Task 8's route.
- Produces: three extra download buttons per version row.

- [ ] **Step 1: Add URL helper** in `recdefs-controllers.js` next to `downloadXmlUrl`:

```javascript
        $scope.artifactUrl = function (prefix, hash, name) {
            return "/narthex/app/rec-defs/" + prefix + "/" + hash + "/artifact/" + name;
        };
```
(Match the exact URL-building style of the existing `downloadXmlUrl` two lines above — if it uses a base-path variable, use the same.)

- [ ] **Step 2: Add buttons** in `recdefs-list.html` after the XSD button (line ~121):

```html
                                <a class="btn btn-default btn-xs" ng-href="{{ artifactUrl(s.prefix, v.hash, 'ontology.ttl') }}" target="_blank">
                                    <i class="fa fa-download"></i> RDFS
                                </a>
                                <a class="btn btn-default btn-xs" ng-href="{{ artifactUrl(s.prefix, v.hash, 'shapes.ttl') }}" target="_blank">
                                    <i class="fa fa-download"></i> SHACL
                                </a>
                                <a class="btn btn-default btn-xs" ng-href="{{ artifactUrl(s.prefix, v.hash, 'context.jsonld') }}" target="_blank">
                                    <i class="fa fa-download"></i> Context
                                </a>
```
NOTE AngularJS constraint (buglog #3492): `{{ }}` in `ng-href` is fine; never in DOM event attributes like `onchange`.

- [ ] **Step 3: Compile + commit**

Run: `make compile` — expected green (asset pipeline runs in dist, compile catches nothing JS-side; that is acceptable, the deploy check covers it).

```bash
git add app/assets/javascripts/recdefs/recdefs-controllers.js public/templates/recdefs-list.html
git commit -m "feat(recdefs-ui): RDFS/SHACL/context download links per version"
```

---

### Task 10: Ace repo make-target + rollout

**Files:**
- Modify: `/home/kiivihal/PocketMapper/recdef_repos/ace/Makefile`
- Narthex: `make bump-version` + deploy to datahub

- [ ] **Step 1: Replace the python RDFS generation** — add to the ace `Makefile` (inspect it first; wire into whatever target currently calls `generate_rdfs.py`):

```makefile
SIP_CORE_JAR = $(HOME)/.m2/repository/eu/delving/sip-core/1.4.1-SNAPSHOT/sip-core-1.4.1-SNAPSHOT.jar

ontology: ## generate ace_ontology.rdf from the recdef via sip-core
	java -cp "$(SIP_CORE_JAR):$(shell cat .sip-core-classpath 2>/dev/null)" RdfsRun latest/ace_record-definition.xml > latest/ace_ontology.rdf
```
Reuse the `RdfsRun` runner from Task 4 (move it into `scripts/RdfsRun.java` with a compile line in the Makefile, and generate `.sip-core-classpath` via the `dependency:build-classpath` command from Task 4). Leave `generate_rdfs.py` in place but mark it deprecated in its docstring ("superseded by sip-core RdfsGenerator — kept for parity reference"). `generate_docs.py`/mkdocs untouched.

- [ ] **Step 2: Verify** `make ontology` produces a parseable RDF file (`python3 -c "import rdflib; print(len(rdflib.Graph().parse('latest/ace_ontology.rdf')))"`).

- [ ] **Step 3: Commit on the `semantics-in-recdef` branch** (same push rule as Task 4: hold until PR #7 lands).

```bash
git add Makefile scripts/RdfsRun.java scripts/generate_rdfs.py
git commit -m "build: generate ontology via sip-core RdfsGenerator (python generator deprecated)"
```

- [ ] **Step 4: Narthex rollout to datahub**

```bash
cd /home/kiivihal/code/scala/narthex
make bump-version
make deploy SSH_HOST=root@datahub.acpt.delving.io ORG_ID=datahub
```
Then verify all four artifact downloads against the live ace 0.2.5 hash (`7f93915f`):

```bash
for a in ontology.rdf ontology.ttl shapes.ttl context.jsonld; do
  curl -sf -o /dev/null -w "%{http_code} $a\n" "https://datahub.acpt.delving.io/narthex/app/rec-defs/ace/7f93915f/artifact/$a"
done
```
Expected: four times `200`. Also click-check the three UI buttons once. Update `.wolf/memory.md` and, if anything bit, `.wolf/buglog.json`.

- [ ] **Step 5: SIP-Creator snapshot** — push sip-creator main to trigger the GitHub Actions snapshot build (`maven-jpackage.yml`), so the preview wiring reaches Bert:

```bash
cd /home/kiivihal/code/java/sip-creator && git push origin main
```
Preview success check (spec fase 3): open a datahub adlib-thesau record in SIP-Creator, output type JSONLD — keys must be compact (`P1_is_identified_by`, not the full URI), FRAMED must nest under the root entity.

---

## Self-review notes

- Spec coverage: §1 attrs -> Task 1; §2 model+emitters -> Tasks 2/3/5/6; §3 preview -> Task 7; §4 endpoints+UI -> Tasks 8/9; §5 ace repo -> Tasks 4/10; §6 fase-checks -> Task 4 step 5 (parity), Task 5 step 6 (shacl vs real output), Task 10 step 5 (preview), Task 10 step 4 (downloads). sip.zip non-change: no task touches SIP packaging — by design.
- Route/spec deviation: spec sketched `/narthex/api/recdefs/:prefix/:version/...`; plan follows the EXISTING `/narthex/app/rec-defs/:prefix/:hash/...` pattern (versions are addressed by hash everywhere else in the recdef UI/API). Deliberate; matches codebase convention.
- Types cross-checked: `generate(RecDef, String)` (Task 3) used in Task 8; `generate(RecDef)` (Task 5) in Task 8; `generateContext/generateFrame` (Task 6) in Tasks 7/8; `Entity.fromRoot` added in Task 6 with test back-fill.
