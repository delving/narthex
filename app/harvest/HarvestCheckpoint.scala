package harvest

import java.io.{File, FileInputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.ZipInputStream

import org.apache.commons.io.{FileUtils, IOUtils}
import play.api.libs.json.{Json, OFormat}

/**
 * Lets a failed full OAI-PMH harvest resume instead of starting over.
 * When a full harvest dies halfway (endpoint stalls, broken page), the pages
 * fetched so far and the resumption token of the next page are parked in the
 * dataset dir; the next retry replays those pages and continues from the token.
 * A 2M-record endpoint that fails every few hours otherwise never finishes
 * (dams-assets, 2026-10).
 */
object HarvestCheckpoint {

  val DIR = "harvest-checkpoint"

  // OAI tokens expire (DAMS: 24h); an older checkpoint is not worth trying
  val MaxAgeMillis: Long = 20L * 60 * 60 * 1000

  case class State(url: String, set: String, prefix: String,
                   token: String, cursor: Int, total: Int,
                   deletedIds: List[String], savedAt: Long)

  implicit val stateFormat: OFormat[State] = Json.format[State]

  private def dir(rootDir: File) = new File(rootDir, DIR)
  private def stateFile(rootDir: File) = new File(dir(rootDir), "state.json")
  private def pagesFile(rootDir: File) = new File(dir(rootDir), "pages.zip")

  /** Moves `pages` (a closed harvest zip) into the checkpoint. */
  def save(rootDir: File, pages: File, state: State): Unit = {
    delete(rootDir)
    FileUtils.moveFile(pages, pagesFile(rootDir))
    FileUtils.writeStringToFile(stateFile(rootDir), Json.stringify(Json.toJson(state)), UTF_8)
  }

  /** The checkpoint for this endpoint, or None (a stale or foreign one is deleted). */
  def load(rootDir: File, url: String, set: String, prefix: String,
           now: Long = System.currentTimeMillis()): Option[(State, File)] = {
    val found = for {
      json <- Option(stateFile(rootDir)).filter(_.exists()).map(FileUtils.readFileToString(_, UTF_8))
      state <- Json.parse(json).asOpt[State]
      if state.url == url && state.set == set && state.prefix == prefix
      if now - state.savedAt < MaxAgeMillis
      if pagesFile(rootDir).exists()
    } yield (state, pagesFile(rootDir))
    if (found.isEmpty) delete(rootDir)
    found
  }

  def delete(rootDir: File): Unit = { FileUtils.deleteQuietly(dir(rootDir)); () }

  /** Feeds every page of a harvest zip to `addPage`, in harvest order. */
  def replay(pages: File)(addPage: String => Unit): Int = {
    val zip = new ZipInputStream(new FileInputStream(pages))
    try {
      Iterator.continually(zip.getNextEntry).takeWhile(_ != null)
        .map(_ => addPage(IOUtils.toString(zip, UTF_8))).size
    } finally zip.close()
  }
}
