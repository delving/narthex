package services

import java.io.File
import play.Logger

class FileRepository(email: String) {

  val SUFFIXES = List(".xml.gz", ".tgz", ".zip")
  val home = new File(System.getProperty("user.home"))
  val root = new File(home, "XML-RAY")
  val repo = new File(root, email.replaceAll("@", "_"))
  val uploaded = new File(repo, "uploaded")
  val analyzing = new File(repo, "analyzing")
  val analyzed = new File(repo, "analyzed")

  def uploadedFile(fileName: String) = new File(uploaded, fileName)

  def listUploadedFiles: Seq[File] = {
    if (uploaded.exists()) {
      uploaded.listFiles.filter(file =>
        file.isFile && !SUFFIXES.filter(suffix => file.getName.endsWith(suffix)).isEmpty
      )
    }
    else {
      List.empty
    }
  }

}

