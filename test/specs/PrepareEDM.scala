package specs

import java.io.{File, FileInputStream, FileOutputStream}
import java.util.zip.{GZIPOutputStream, ZipEntry, ZipOutputStream}

import dataset.{SipRepo, SourceRepo}
import org.apache.commons.io.IOUtils
import record.PocketParser
import services.FileHandling

trait PrepareEDM {

  val edmDirNames = List("ton-smits", "difo", "amsterdam")

  val naveDomain = "http://nave"
  val sipsDir = new File(getClass.getResource("/edm").getFile)
  val sipZipFile = new File(sipsDir, "test-edm.sip.zip")

  def copyFile(zos: ZipOutputStream, sourceFile: File): Unit = {
    val file = if (sourceFile.getName == "source.xml") {
      val gzFile = new File(sourceFile.getParentFile, "source.xml.gz")
      val gz = new GZIPOutputStream(new FileOutputStream(gzFile))
      val is = new FileInputStream(sourceFile)
      IOUtils.copy(is, gz)
      gz.close()
      gzFile
    }
    else {
      sourceFile
    }
    println(s"including $file")
    zos.putNextEntry(new ZipEntry(file.getName))
    val is = new FileInputStream(file)
    IOUtils.copy(is, zos)
    zos.closeEntry()
  }

  def createSipRepoFromDir(dirNameIndex: Int): SipRepo = {
    val dirName = edmDirNames(dirNameIndex)
    val source = new File(sipsDir, dirName)
    val zos = new ZipOutputStream(new FileOutputStream(sipZipFile))
    source.listFiles().toList.filter(!_.getName.endsWith(".gz")).map(f => copyFile(zos, f))
    zos.close()
    println(s"created $sipZipFile")
    new SipRepo(sipsDir, dirName, naveDomain)
  }

  def createSourceRepo: SourceRepo = {
    val sourceRepoDir = FileHandling.clearDir(new File(s"/tmp/narthex-test/source-repo"))
    SourceRepo.createClean(sourceRepoDir, PocketParser.POCKET_SOURCE_FACTS)
  }
}
