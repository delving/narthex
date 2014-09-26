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

import java.io.{File, PrintWriter}

import org.apache.commons.io.FileUtils
import services.RecordHandling.RawRecord

import scala.collection.mutable
import scala.io.Source

class SourceRepo(val dir: File, recordRoot: String, uniqueId: String) extends RecordHandling {

  def mkdirs = dir.mkdirs()

  def fileList: List[File] = {
    val files = dir.listFiles()
    if (files == null) List.empty[File] else files.toList
  }

  def numberString(number: Int): String = "%05d".format(number)

  def fileNumber(file: File): Int = {
    val s = file.getName
    val num = s.substring(0, s.indexOf('.'))
    num.toInt
  }

  def xmlName(number: Int): String = s"${numberString(number)}.xml"

  def idsName(number: Int): String = s"${numberString(number)}.ids"

  def xmlFile(number: Int): File = new File(dir, xmlName(number))

  def idsFile(number: Int): File = new File(dir, idsName(number))

  def idsFile(xmlFile: File): File = idsFile(fileNumber(xmlFile))
  
  def intersectionFile(oldFile: File, newFile: File): File = new File(dir, s"${oldFile.getName}_${newFile.getName}")

  def xmlFiles = fileList.filter(f => f.isFile && f.getName.endsWith(".xml")).sortBy(_.getName).reverse

  def nextFileNumber(files: List[File]): Int = {
    files.headOption match {
      case Some(file) =>
        fileNumber(file) + 1
      case None =>
        0
    }
  }

  def nextFileNumber: Int = nextFileNumber(xmlFiles)

  def acceptFile(file: File): Option[File] = {
    val files = xmlFiles
    val fileNumber = nextFileNumber(files)
    val target = xmlFile(fileNumber)
    FileUtils.moveFile(file, target)
    var idSet = new mutable.HashSet[String]()
    val parser = new RawRecordParser(recordRoot, uniqueId)
    def sendProgress(percent: Int): Boolean = true
    def receiveRecord(record: RawRecord): Unit = idSet.add(record.id)
    def source = Source.fromFile(target)
    parser.parse(source, receiveRecord, -1, sendProgress)
    if (!idSet.nonEmpty) None else {
      val newIdsFile = idsFile(fileNumber)
      FileUtils.write(newIdsFile, idSet.toList.sorted.mkString("\n"))
      var idsFiles = files.map(idsFile)
      idsFiles.foreach { idsFile =>
        val ids = Source.fromFile(idsFile).getLines().filter(idSet.contains)
        if (ids.nonEmpty) {
          val intersection = intersectionFile(idsFile, newIdsFile)
          Some(new PrintWriter(intersection)).foreach{
            p =>
              p.write(ids.mkString("\n"))
              p.close()
          }
        }
      }
      Some(target)
    }
  }

  def acceptPage(page: String): File = {
    xmlFile(10)
  }

}
