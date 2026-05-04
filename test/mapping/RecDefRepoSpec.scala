package mapping

import java.io.File
import java.nio.file.Files

import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec._
import org.scalatest.matchers._

class RecDefRepoSpec extends AnyFlatSpec with should.Matchers with BeforeAndAfterEach {

  private var orgRoot: File = _

  override def beforeEach(): Unit = {
    orgRoot = Files.createTempDirectory("recdef-repo-spec-").toFile
  }

  override def afterEach(): Unit = {
    if (orgRoot != null && orgRoot.exists()) FileUtils.deleteDirectory(orgRoot)
  }

  private def recDefXml(prefix: String, version: String): String =
    s"""<?xml version="1.0"?>
       |<record-definition prefix="$prefix" version="$version" flat="false">
       |  <namespaces/>
       |  <root tag="root"/>
       |</record-definition>
       |""".stripMargin

  private def xsdXml(version: String): String =
    s"""<?xml version="1.0"?>
       |<xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" version="$version"/>
       |""".stripMargin

  "RecDefRepo.saveVersion" should "store XML, derive schemaVersion, and write metadata.json" in {
    val repo = new RecDefRepo(orgRoot)
    val v = repo.saveVersion("edm", recDefXml("edm", "5.2.6"), None, "upload", Some("first"))

    v.schemaVersion shouldBe "edm_5.2.6"
    v.hasXsd shouldBe false
    v.xsdFilename shouldBe None
    v.source shouldBe "upload"
    v.notes shouldBe Some("first")

    val xmlFile = new File(new File(new File(repo.recDefsDir, "edm"), "versions"), v.filename)
    xmlFile.exists() shouldBe true
    new File(new File(repo.recDefsDir, "edm"), "metadata.json").exists() shouldBe true

    repo.getCurrent("edm").map(_.version.hash) shouldBe Some(v.hash)
  }

  it should "be idempotent — re-saving the same XML returns the existing version" in {
    val repo = new RecDefRepo(orgRoot)
    val xml = recDefXml("edm", "5.2.6")
    val v1 = repo.saveVersion("edm", xml, None, "upload", None)
    val v2 = repo.saveVersion("edm", xml, None, "upload", None)
    v1.hash shouldBe v2.hash
    v1.filename shouldBe v2.filename
    repo.listVersions("edm") should have size 1
  }

  it should "reject XML missing <record-definition prefix=... version=...>" in {
    val repo = new RecDefRepo(orgRoot)
    an[IllegalArgumentException] should be thrownBy
      repo.saveVersion("edm", "<not-a-recdef/>", None, "upload", None)
  }

  it should "store paired XSD when provided and set hasXsd=true" in {
    val repo = new RecDefRepo(orgRoot)
    val v = repo.saveVersion("edm", recDefXml("edm", "5.2.6"), Some(xsdXml("5.2.6")), "upload", None)
    v.hasXsd shouldBe true
    v.xsdFilename shouldBe defined
    val xsdFile = new File(new File(new File(repo.recDefsDir, "edm"), "versions"), v.xsdFilename.get)
    xsdFile.exists() shouldBe true
  }

  it should "auto-set first version as currentVersion" in {
    val repo = new RecDefRepo(orgRoot)
    val v = repo.saveVersion("edm", recDefXml("edm", "5.2.6"), None, "upload", None)
    repo.getCurrent("edm").map(_.version.hash) shouldBe Some(v.hash)
  }

  it should "preserve currentVersion when adding a second version" in {
    val repo = new RecDefRepo(orgRoot)
    val v1 = repo.saveVersion("edm", recDefXml("edm", "5.2.6"), None, "upload", None)
    val v2 = repo.saveVersion("edm", recDefXml("edm", "5.2.7"), None, "upload", None)
    v1.hash should not be v2.hash
    repo.getCurrent("edm").map(_.version.hash) shouldBe Some(v1.hash)
    repo.listVersions("edm") should have size 2
  }

  "RecDefRepo.setCurrent" should "switch the current version" in {
    val repo = new RecDefRepo(orgRoot)
    val v1 = repo.saveVersion("edm", recDefXml("edm", "5.2.6"), None, "upload", None)
    val v2 = repo.saveVersion("edm", recDefXml("edm", "5.2.7"), None, "upload", None)
    repo.setCurrent("edm", v2.hash) shouldBe true
    repo.getCurrent("edm").map(_.version.hash) shouldBe Some(v2.hash)
  }

  it should "return false for an unknown hash" in {
    val repo = new RecDefRepo(orgRoot)
    repo.saveVersion("edm", recDefXml("edm", "5.2.6"), None, "upload", None)
    repo.setCurrent("edm", "deadbeef") shouldBe false
  }

  "RecDefRepo.deleteVersion" should "refuse to delete the current version" in {
    val repo = new RecDefRepo(orgRoot)
    val v = repo.saveVersion("edm", recDefXml("edm", "5.2.6"), None, "upload", None)
    repo.deleteVersion("edm", v.hash) shouldBe false
    repo.listVersions("edm") should have size 1
  }

  it should "delete a non-current version + remove its files" in {
    val repo = new RecDefRepo(orgRoot)
    val v1 = repo.saveVersion("edm", recDefXml("edm", "5.2.6"), None, "upload", None)
    val v2 = repo.saveVersion("edm", recDefXml("edm", "5.2.7"), Some(xsdXml("5.2.7")), "upload", None)
    repo.deleteVersion("edm", v2.hash) shouldBe true
    repo.listVersions("edm").map(_.hash) should contain only v1.hash
    val versionsDir = new File(new File(repo.recDefsDir, "edm"), "versions")
    new File(versionsDir, v2.filename).exists() shouldBe false
    new File(versionsDir, v2.xsdFilename.get).exists() shouldBe false
  }

  "RecDefRepo.listSummaries" should "return one summary per prefix with counts" in {
    val repo = new RecDefRepo(orgRoot)
    repo.saveVersion("edm", recDefXml("edm", "5.2.6"), None, "upload", None)
    repo.saveVersion("edm", recDefXml("edm", "5.2.7"), None, "upload", None)
    repo.saveVersion("ace", recDefXml("ace", "1.0.0"), None, "upload", None)
    val summaries = repo.listSummaries()
    summaries.map(_.prefix) should contain theSameElementsAs Seq("ace", "edm")
    summaries.find(_.prefix == "edm").map(_.versionCount) shouldBe Some(2)
    summaries.find(_.prefix == "ace").map(_.versionCount) shouldBe Some(1)
  }

  "RecDefRepo.parseSchemaVersion" should "extract prefix_version from root attrs" in {
    RecDefRepo.parseSchemaVersion(recDefXml("edm", "5.2.6")) shouldBe Some("edm_5.2.6")
  }

  it should "return None when prefix or version attr is missing" in {
    val xml =
      """<?xml version="1.0"?>
        |<record-definition flat="false"/>
        |""".stripMargin
    RecDefRepo.parseSchemaVersion(xml) shouldBe None
  }

  it should "return None for malformed XML" in {
    RecDefRepo.parseSchemaVersion("<broken") shouldBe None
  }

  // -------- migration --------

  private def writeFactoryPrefix(factoryDir: File, prefix: String, schemaVersion: String, withXsd: Boolean = true): Unit = {
    val prefixDir = new File(factoryDir, prefix)
    prefixDir.mkdirs()
    FileUtils.writeStringToFile(
      new File(prefixDir, s"${prefix}_${schemaVersion}_record-definition.xml"),
      recDefXml(prefix, schemaVersion),
      "UTF-8"
    )
    if (withXsd) {
      FileUtils.writeStringToFile(
        new File(prefixDir, s"${prefix}_${schemaVersion}_validation.xsd"),
        xsdXml(schemaVersion),
        "UTF-8"
      )
    }
  }

  "RecDefRepo.migrateFromFactoryOnce" should "import factory recdefs into versioned layout" in {
    val factoryDir = new File(orgRoot, "factory")
    writeFactoryPrefix(factoryDir, "edm", "5.2.6")
    writeFactoryPrefix(factoryDir, "ace", "1.0.0")

    val repo = new RecDefRepo(orgRoot)
    repo.migrateFromFactoryOnce(factoryDir)

    repo.listPrefixes() should contain theSameElementsAs Seq("ace", "edm")
    val edmCurrent = repo.getCurrent("edm").get
    edmCurrent.version.schemaVersion shouldBe "edm_5.2.6"
    edmCurrent.version.source shouldBe "migration_from_factory"
    edmCurrent.version.hasXsd shouldBe true
    edmCurrent.validationFileOpt shouldBe defined
  }

  it should "be idempotent — second run does not duplicate versions" in {
    val factoryDir = new File(orgRoot, "factory")
    writeFactoryPrefix(factoryDir, "edm", "5.2.6")

    val repo = new RecDefRepo(orgRoot)
    repo.migrateFromFactoryOnce(factoryDir)
    repo.migrateFromFactoryOnce(factoryDir)

    repo.listVersions("edm") should have size 1
  }

  it should "succeed without xsd and flag hasXsd=false" in {
    val factoryDir = new File(orgRoot, "factory")
    writeFactoryPrefix(factoryDir, "edm", "5.2.6", withXsd = false)

    val repo = new RecDefRepo(orgRoot)
    repo.migrateFromFactoryOnce(factoryDir)

    val v = repo.getCurrent("edm").get
    v.version.hasXsd shouldBe false
    v.validationFileOpt shouldBe None
  }

  it should "skip prefixes whose record-definition is malformed and continue with others" in {
    val factoryDir = new File(orgRoot, "factory")
    writeFactoryPrefix(factoryDir, "edm", "5.2.6")

    // Corrupt prefix dir: file exists with right suffix but XML is broken
    val brokenPrefixDir = new File(factoryDir, "broken")
    brokenPrefixDir.mkdirs()
    FileUtils.writeStringToFile(
      new File(brokenPrefixDir, "broken_X_record-definition.xml"),
      "<not-a-recdef/>",
      "UTF-8"
    )

    val repo = new RecDefRepo(orgRoot)
    repo.migrateFromFactoryOnce(factoryDir)

    repo.listPrefixes() should contain("edm")
    repo.listPrefixes() should not contain("broken")
  }

  it should "no-op when factory dir does not exist" in {
    val repo = new RecDefRepo(orgRoot)
    repo.migrateFromFactoryOnce(new File(orgRoot, "no-such-factory"))
    repo.listPrefixes() shouldBe empty
  }
}
