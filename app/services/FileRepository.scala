package services

import java.io.File
import play.Logger

class FileRepository(name : String) {
  val SUFFIXES = List(".xml.gz", ".tgz", ".zip")
  val home = new File(System.getProperty("user.home"))
  val root = new File(home, "XML-RAY")
  val repo = new File(root, name)

  def file(fileName : String) = new File(repo, fileName)

  def list : Seq[File] = {
    if (repo.exists()) {
      repo.listFiles.filter(file =>
        file.isFile && !SUFFIXES.filter(suffix => file.getName.endsWith(suffix)).isEmpty
      )
    }
    else {
      List.empty
    }
  }
}