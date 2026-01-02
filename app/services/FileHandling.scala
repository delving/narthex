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

import com.github.luben.zstd.{ZstdInputStream, ZstdOutputStream}
import org.apache.commons.io.FileUtils._
import org.apache.commons.io.input.{BOMInputStream, CountingInputStream}
import play.api.Logger

import scala.io.Source
import scala.sys.process._
import scala.util.Try
import scala.util.control.NonFatal

object FileHandling {

  private val logger = Logger(getClass)

  def clearDir(dir: File) = {
    deleteQuietly(dir)
    dir.getParentFile.mkdirs()
    dir.mkdir()
    dir
  }

  def createReader(inputStream: InputStream): BufferedReader = new BufferedReader(new InputStreamReader(new BOMInputStream(inputStream), "UTF-8"))

  def createReader(file: File): BufferedReader = {
    val fis = new FileInputStream(file)
    try {
      createReader(fis)
    } catch {
      case NonFatal(e) =>
        fis.close()
        throw e
    }
  }

  def readerCounting(file: File): (BufferedReader, CountingInputStream) = {
    val fis: FileInputStream = new FileInputStream(file)
    val cis = new CountingInputStream(fis)
    val name = file.getName
    val inputStream: InputStream = if (name.endsWith(".zst") || name.endsWith(".xml.zst")) {
      new ZstdInputStream(cis)
    } else if (name.endsWith(".gz")) {
      new GZIPInputStream(cis)
    } else {
      cis
    }
    val is = new InputStreamReader(inputStream, "UTF-8")
    val br = new BufferedReader(is)
    (br, cis)
  }

  def createWriter(file: File) = {
    val fos = new FileOutputStream(file)
    try {
      new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"))
    } catch {
      case NonFatal(e) =>
        fos.close()
        throw e
    }
  }

  def appender(file: File) = {
    val fos = new FileOutputStream(file, true)
    try {
      new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"))
    } catch {
      case NonFatal(e) =>
        fos.close()
        throw e
    }
  }

  def createWriter(outputStream: OutputStream) = new BufferedWriter(new OutputStreamWriter(outputStream, "UTF-8"))

  /** Create a writer that compresses output with ZSTD (level 3 = good balance of speed and compression) */
  def createZstdWriter(file: File): BufferedWriter = {
    val fos = new FileOutputStream(file)
    try {
      val zstdOut = new ZstdOutputStream(fos, 3)
      new BufferedWriter(new OutputStreamWriter(zstdOut, "UTF-8"), 65536)  // 64KB buffer
    } catch {
      case NonFatal(e) =>
        fos.close()
        throw e
    }
  }

  abstract class ReadProgress {
    def getPercentRead: Int
  }

  class FileReadProgress(fileSize: Long, counter: CountingInputStream) extends ReadProgress {
    override def getPercentRead: Int = ((100 * counter.getByteCount) / fileSize).toInt
  }

  def sourceFromFile(file: File): (Source, ReadProgress) = {
    if (file.getName.endsWith(".zip")) {
      Try {
        val zipFile = new ZipFile(file)
        val zc = new ZipConcatXML(zipFile)
        (zc, zc.readProgress)
      }.recover {
        case NonFatal(e) =>
          throw new RuntimeException(s"Failed to open zip file: ${file.getName}", e)
      }.get
    }
    else if (file.getName.endsWith(".xml.zst") || file.getName.endsWith(".zst")) {
      val is = new FileInputStream(file)
      try {
        val cs = new CountingInputStream(is)
        val zstd = new ZstdInputStream(cs)
        val bis = new BOMInputStream(zstd)
        (Source.fromInputStream(bis, "UTF-8"), new FileReadProgress(file.length(), cs))
      } catch {
        case NonFatal(e) =>
          is.close()
          throw new RuntimeException(s"Failed to open ZSTD compressed file: ${file.getName}", e)
      }
    }
    else if (file.getName.endsWith(".xml.gz")) {
      val is = new FileInputStream(file)
      try {
        val cs = new CountingInputStream(is)
        val gz = new GZIPInputStream(cs)
        val bis = new BOMInputStream(gz)
        (Source.fromInputStream(bis, "UTF-8"), new FileReadProgress(file.length(), cs))
      } catch {
        case NonFatal(e) =>
          is.close()
          throw new RuntimeException(s"Failed to open gzipped XML file: ${file.getName}", e)
      }
    }
    else if (file.getName.endsWith(".xml")) {
      val is = new FileInputStream(file)
      try {
        val cs = new CountingInputStream(is)
        val bis = new BOMInputStream(cs)
        (Source.fromInputStream(bis, "UTF-8"), new FileReadProgress(file.length(), cs))
      } catch {
        case NonFatal(e) =>
          is.close()
          throw new RuntimeException(s"Failed to open XML file: ${file.getName}", e)
      }
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

  class ZipConcatXML(val file: ZipFile) extends Source {
    var entries = file.entries()
    var entry: Option[ZipEntry] = None
    var entryReader: Reader = null
    var nextChar: Int = -1
    var charCount = 0L
    var closed = false

    class ZipReadProgress(size: Long) extends ReadProgress {
      override def getPercentRead: Int = ((100 * charCount) / size).toInt
    }

    private def closeCurrentReader(): Unit = {
      if (entryReader != null) {
        try {
          entryReader.close()
        } catch {
          case NonFatal(_) => // Ignore errors during cleanup
        }
        entryReader = null
      }
    }

    private def closeFile(): Unit = {
      if (!closed) {
        closeCurrentReader()
        try {
          file.close()
        } catch {
          case NonFatal(_) => // Ignore errors during cleanup
        }
        closed = true
      }
    }

    class ZipEntryIterator extends Iterator[Char] {
      override def hasNext: Boolean = {
        if (closed) return false
        
        entry match {
          case Some(zipEntry) =>
            if (nextChar >= 0) {
              true
            }
            else {
              closeCurrentReader()
              entry = None
              hasNext
            }

          case None =>
            if (entries.hasMoreElements) {
              try {
                entry = Some(entries.nextElement())
                val is = file.getInputStream(entry.get)
                entryReader = new InputStreamReader(new BOMInputStream(new BufferedInputStream(is)), "UTF-8")
                nextChar = entryReader.read()
                charCount += 1
                hasNext
              } catch {
                case NonFatal(e) =>
                  closeFile()
                  throw new RuntimeException("Error reading zip entry", e)
              }
            }
            else {
              closeFile()
              false
            }
        }
      }

      override def next(): Char = {
        if (closed || entryReader == null) {
          throw new IllegalStateException("Iterator is closed")
        }
        try {
          val c = nextChar
          nextChar = entryReader.read()
          charCount += 1
          c.toChar
        } catch {
          case NonFatal(e) =>
            closeFile()
            throw new RuntimeException("Error reading character", e)
        }
      }
    }

    override protected val iter: Iterator[Char] = new ZipEntryIterator

    def readProgress = new ZipReadProgress(getZipFileSize(file))
    
    // Override close to ensure proper cleanup
    override def close(): Unit = closeFile()
  }

  class FileConcatXML(directory: File) extends Source {
    val fileList: List[File] = directory.listFiles().toList.filter(f => f.getName.endsWith(".xml")).sortBy(_.getName)
    var entries = fileList
    var totalLength = (0L /: fileList.map(_.length()))(_ + _)
    var entry: Option[Reader] = None
    var nextChar: Int = -1
    var charCount = 0L
    var closed = false

    private def closeCurrentReader(): Unit = {
      entry.foreach { reader =>
        try {
          reader.close()
        } catch {
          case NonFatal(_) => // Ignore errors during cleanup
        }
      }
      entry = None
    }

    private def closeAll(): Unit = {
      if (!closed) {
        closeCurrentReader()
        closed = true
      }
    }

    class FileIterator extends Iterator[Char] {
      override def hasNext: Boolean = {
        if (closed) return false
        
        entry.map { reader =>
          if (nextChar >= 0) {
            true
          }
          else {
            closeCurrentReader()
            hasNext
          }
        } getOrElse {
          if (entries.nonEmpty) {
            try {
              val fis = new FileInputStream(entries.head)
              val reader = new InputStreamReader(new BOMInputStream(new BufferedInputStream(fis)), "UTF-8")
              entry = Some(reader)
              entries = entries.tail
              nextChar = entry.get.read()
              charCount += 1
              hasNext
            } catch {
              case NonFatal(e) =>
                closeAll()
                throw new RuntimeException(s"Error reading file: ${entries.head.getName}", e)
            }
          }
          else {
            closeAll()
            false
          }
        }
      }

      override def next(): Char = {
        if (closed || entry.isEmpty) {
          throw new IllegalStateException("Iterator is closed")
        }
        try {
          val c = nextChar
          nextChar = entry.get.read()
          charCount += 1
          c.toChar
        } catch {
          case NonFatal(e) =>
            closeAll()
            throw new RuntimeException("Error reading character", e)
        }
      }
    }

    override protected val iter: Iterator[Char] = new FileIterator
    
    // Override close to ensure proper cleanup
    override def close(): Unit = closeAll()

    class FileReadProgress() extends ReadProgress {
      override def getPercentRead: Int = ((100 * charCount) / totalLength).toInt
    }

    lazy val readProgress = new FileReadProgress()
  }

  def ensureGitRepo(directory: File): Boolean = {
    val hiddenGit = new File(directory, ".git")
    if (!hiddenGit.exists()) {
      val init = s"git init ${directory.getAbsolutePath}".!!
      logger.debug(s"git init: $init")
    }
    hiddenGit.exists()
  }

  def gitCommit(file: File, comment: String): Boolean = {
    // assuming all files are in the git root?
    val home = file.getParentFile.getAbsolutePath
    val name = file.getName
    val addCommand = Process(Seq("git", "-C", home, "add", name))
    logger.debug(s"git add: $addCommand")
    val addResult = addCommand.!!
    logger.debug(s"addResult: $addResult")
    val commitCommand = Process(Seq("git", "-C", home, "commit", "-m", comment, name))
    logger.debug(s"git commit: $commitCommand")
    val commitResult = commitCommand.!!
    logger.debug(s"commitResult: $commitResult")

    true
  }

}
