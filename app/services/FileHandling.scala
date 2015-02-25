//===========================================================================
//    Copyright 2014 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//===========================================================================

package services

import java.io._
import java.util.zip.{GZIPInputStream, ZipEntry, ZipFile}

import org.apache.commons.io.FileUtils._
import org.apache.commons.io.input.{BOMInputStream, CountingInputStream}
import play.api.Logger

import scala.io.Source
import scala.sys.process._

object FileHandling {

  def clearDir(dir: File) = {
    deleteQuietly(dir)
    dir.getParentFile.mkdirs()
    dir.mkdir()
    dir
  }

  def reader(inputStream: InputStream): BufferedReader = new BufferedReader(new InputStreamReader(new BOMInputStream(inputStream), "UTF-8"))

  def reader(file: File): BufferedReader = reader(new FileInputStream(file))

  def readerCounting(file: File): (BufferedReader, CountingInputStream) = {
    val fis: FileInputStream = new FileInputStream(file)
    val cis = new CountingInputStream(fis)
    val is = new InputStreamReader(cis, "UTF-8")
    val br = new BufferedReader(is)
    (br, cis)
  }

  def writer(file: File) = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"))

  def appender(file: File) = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8"))

  def writer(outputStream: OutputStream) = new BufferedWriter(new OutputStreamWriter(outputStream, "UTF-8"))

  abstract class ReadProgress {
    def getPercentRead: Int
  }

  class FileReadProgress(fileSize: Long, counter: CountingInputStream) extends ReadProgress {
    override def getPercentRead: Int = ((100 * counter.getByteCount) / fileSize).toInt
  }

  def sourceFromFile(file: File): (Source, ReadProgress) = {
    if (file.getName.endsWith(".zip")) {
      val zc = new ZipConcatXML(new ZipFile(file))
      (zc, zc.readProgress)
    }
    else if (file.getName.endsWith(".xml.gz")) {
      val is = new FileInputStream(file)
      val cs = new CountingInputStream(is)
      val gz = new GZIPInputStream(cs)
      val bis = new BOMInputStream(gz)
      (Source.fromInputStream(bis, "UTF-8"), new FileReadProgress(file.length(), cs))
    }
    else if (file.getName.endsWith(".xml")) {
      val is = new FileInputStream(file)
      val cs = new CountingInputStream(is)
      val bis = new BOMInputStream(cs)
      (Source.fromInputStream(bis, "UTF-8"), new FileReadProgress(file.length(), cs))
    }
    else {
      throw new RuntimeException(s"Unrecognized source from: ${file.getName}")
    }
  }

  def getZipFileSize(zipFile: ZipFile) = {
    val e = zipFile.entries()
    var size = 0L
    while (e.hasMoreElements) {
      size += e.nextElement().getSize
    }
    size
  }

  //  def createDigest = MessageDigest.getInstance("SHA1")
  //  def hex(digest: MessageDigest) = digest.digest().map("%02X" format _).mkString

  private def unzipXML(file: File, inputStream: InputStream) = {
    val stream = if (file.getName.endsWith(".gz")) new GZIPInputStream(inputStream) else inputStream
    new BOMInputStream(stream)
  }

  class ZipConcatXML(val file: ZipFile) extends Source {
    var entries = file.entries()
    var entry: Option[ZipEntry] = None
    var entryReader: Reader = null
    var nextChar: Int = -1
    var charCount = 0L

    class ZipReadProgress(size: Long) extends ReadProgress {
      override def getPercentRead: Int = ((100 * charCount) / size).toInt
    }

    class ZipEntryIterator extends Iterator[Char] {
      override def hasNext: Boolean = {
        entry match {
          case Some(zipEntry) =>
            if (nextChar >= 0) {
              true
            }
            else {
              entry = None
              hasNext
            }

          case None =>
            if (entries.hasMoreElements) {
              // check for .xml
              entry = Some(entries.nextElement())
              val is = file.getInputStream(entry.get)
              entryReader = new InputStreamReader(new BOMInputStream(new BufferedInputStream(is)), "UTF-8")
              nextChar = entryReader.read()
              charCount += 1
              hasNext
            }
            else {
              false
            }
        }
      }

      override def next(): Char = {
        val c = nextChar
        nextChar = entryReader.read()
        charCount += 1
        c.toChar
      }
    }

    override protected val iter: Iterator[Char] = new ZipEntryIterator

    def readProgress = new ZipReadProgress(getZipFileSize(file))
  }

  class FileConcatXML(directory: File) extends Source {
    val fileList: List[File] = directory.listFiles().toList.filter(f => f.getName.endsWith(".xml")).sortBy(_.getName)
    var entries = fileList
    var totalLength = (0L /: fileList.map(_.length()))(_ + _)
    var entry: Option[Reader] = None
    var nextChar: Int = -1
    var charCount = 0L

    class FileIterator extends Iterator[Char] {
      override def hasNext: Boolean = entry.map { reader =>
        if (nextChar >= 0) {
          true
        }
        else {
          reader.close()
          entry = None
          hasNext
        }
      } getOrElse {
        if (entries.nonEmpty) {
          entry = Some(new InputStreamReader(new BOMInputStream(new BufferedInputStream(new FileInputStream(entries.head))), "UTF-8"))
          entries = entries.tail
          nextChar = entry.get.read()
          charCount += 1
          hasNext
        }
        else {
          false
        }
      }

      override def next(): Char = {
        val c = nextChar
        nextChar = entry.get.read()
        charCount += 1
        c.toChar
      }
    }

    override protected val iter: Iterator[Char] = new FileIterator

    class FileReadProgress() extends ReadProgress {
      override def getPercentRead: Int = ((100 * charCount) / totalLength).toInt
    }

    lazy val readProgress = new FileReadProgress()
  }

  def ensureGitRepo(directory: File): Boolean = {
    val hiddenGit = new File(directory, ".git")
    if (!hiddenGit.exists()) {
      val init = s"git init ${directory.getAbsolutePath}".!!
      Logger.info(s"git init: $init")
    }
    hiddenGit.exists()
  }

  def gitCommit(file: File, comment: String): Boolean = {
    // assuming all files are in the git root?
    val home = file.getParentFile.getAbsolutePath
    val name = file.getName
    val addCommand = Process(Seq("git", "-C", home, "add", name))
    Logger.info(s"git add: $addCommand")
    val add = addCommand.!!
    val commitCommand = Process(Seq("git", "-C", home, "commit", "-m", comment, name))
    Logger.info(s"git commit: $commitCommand")
    val commit = commitCommand.!!
    true
  }

}
