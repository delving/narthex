//===========================================================================
//    Copyright 2024 Delving B.V.
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

package controllers

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.matching.Regex
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.cache.AsyncCacheApi
import play.api.Logging

/**
 * Controller for SIP Creator downloads page.
 * Fetches and parses directory listings from download.delving.io
 */
@Singleton
class SipCreatorDownloadsController @Inject()(
  ws: WSClient,
  cache: AsyncCacheApi
)(implicit ec: ExecutionContext) extends InjectedController with Logging {

  private val BaseUrl = "https://download.delving.io/build/sip-creator"
  private val ReleasesUrl = s"$BaseUrl/releases/"
  private val SnapshotsUrl = s"$BaseUrl/snapshots/"
  private val CacheKey = "sip-creator-downloads"
  private val CacheDuration = 1.hour

  // JSON format for download file info
  case class DownloadFile(os: String, arch: String, ext: String, url: String, filename: String)
  case class VersionInfo(version: String, date: Option[String], files: Seq[DownloadFile])
  case class DownloadsResponse(releases: Seq[VersionInfo], snapshots: Seq[VersionInfo])

  implicit val downloadFileWrites: Writes[DownloadFile] = Json.writes[DownloadFile]
  implicit val versionInfoWrites: Writes[VersionInfo] = Json.writes[VersionInfo]
  implicit val downloadsResponseWrites: Writes[DownloadsResponse] = Json.writes[DownloadsResponse]

  /**
   * Get SIP Creator downloads list (releases and snapshots)
   * Uses caching to avoid hitting the server on every request
   */
  def getDownloads(refresh: Boolean = false) = Action.async { _ =>
    if (refresh) {
      cache.remove(CacheKey).flatMap(_ => fetchAndCacheDownloads())
    } else {
      cache.getOrElseUpdate(CacheKey, CacheDuration)(fetchDownloadsData()).map { response =>
        Ok(Json.toJson(response))
      }
    }
  }

  private def fetchAndCacheDownloads(): Future[Result] = {
    fetchDownloadsData().map { response =>
      Ok(Json.toJson(response))
    }
  }

  private def fetchDownloadsData(): Future[DownloadsResponse] = {
    val releasesFuture = fetchDirectoryListing(ReleasesUrl, isSnapshot = false)
    val snapshotsFuture = fetchDirectoryListing(SnapshotsUrl, isSnapshot = true)

    for {
      releases <- releasesFuture
      snapshots <- snapshotsFuture
    } yield DownloadsResponse(releases, snapshots)
  }

  private def fetchDirectoryListing(url: String, isSnapshot: Boolean): Future[Seq[VersionInfo]] = {
    ws.url(url)
      .withRequestTimeout(30.seconds)
      .get()
      .map { response =>
        if (response.status == 200) {
          parseDirectoryListing(response.body, url, isSnapshot)
        } else {
          logger.warn(s"Failed to fetch $url: ${response.status}")
          Seq.empty
        }
      }
      .recover {
        case e: Exception =>
          logger.error(s"Error fetching $url", e)
          Seq.empty
      }
  }

  // Regex patterns for parsing filenames
  // Release: sip-creator-{VERSION}-{OS}-{ARCH}.{EXT}
  // Snapshot: sip-creator-{VERSION}-SNAPSHOT-{TIMESTAMP}-{COMMIT}.{EXT}
  // Universal JAR: sip-creator-{VERSION}.jar or sip-creator-{VERSION}-SNAPSHOT-{TIMESTAMP}-{COMMIT}.jar

  private val ReleasePattern: Regex =
    """sip-creator-([^-]+-?(?:RC\d+|BETA\d*|ALPHA\d*)?)-([a-z]+)-([a-z0-9_]+)\.(tar\.gz|tar\.xz|dmg|msi|zip|jar)""".r

  private val ReleaseJarPattern: Regex =
    """sip-creator-([^-]+-?(?:RC\d+|BETA\d*|ALPHA\d*)?)\.(jar)""".r

  private val SnapshotPattern: Regex =
    """sip-creator-([^-]+)-SNAPSHOT-(\d{4}-\d{2}-\d{2}T\d{6})-([a-f0-9]+)-([a-z]+)-([a-z0-9_]+)\.(tar\.gz|tar\.xz|dmg|msi|zip|jar)""".r

  private val SnapshotJarPattern: Regex =
    """sip-creator-([^-]+)-SNAPSHOT-(\d{4}-\d{2}-\d{2}T\d{6})-([a-f0-9]+)\.(jar)""".r

  private def parseDirectoryListing(html: String, baseUrl: String, isSnapshot: Boolean): Seq[VersionInfo] = {
    // Parse HTML to extract file links (nginx autoindex format with title attribute)
    // <a href="filename" title="filename">filename</a>
    val linkPattern = """<a href="([^"]+)"[^>]*>""".r
    val filenames = linkPattern.findAllMatchIn(html).map(_.group(1)).toSeq
      .filterNot(_.startsWith(".."))
      .filterNot(_.endsWith("/"))

    val files = filenames.flatMap { filename =>
      parseFilename(filename, baseUrl, isSnapshot)
    }

    // Group by version
    val byVersion = files.groupBy(_._1).map { case (version, versionFiles) =>
      // For snapshots, use the latest timestamp from all files in this commit group
      val dates = versionFiles.flatMap(_._2)
      val latestDate = if (dates.nonEmpty) Some(dates.max) else None
      val downloadFiles = versionFiles.map(_._3)
      VersionInfo(version, latestDate, downloadFiles)
    }.toSeq

    // Sort by version (newest first)
    if (isSnapshot) {
      // For snapshots, sort by latest timestamp in each commit group
      byVersion.sortBy { v =>
        v.date.getOrElse("")
      }.reverse
    } else {
      // For releases, sort by semantic version
      byVersion.sortBy(v => parseSemanticVersion(v.version)).reverse
    }
  }

  private def parseFilename(filename: String, baseUrl: String, isSnapshot: Boolean): Option[(String, Option[String], DownloadFile)] = {
    val url = baseUrl + filename

    if (isSnapshot) {
      // Try snapshot with OS/arch first
      // Group by commit hash (not timestamp) since CI builds platforms sequentially
      SnapshotPattern.findFirstMatchIn(filename).map { m =>
        val version = s"${m.group(1)}-SNAPSHOT"
        val timestamp = m.group(2)
        val commit = m.group(3)
        val os = normalizeOs(m.group(4))
        val arch = m.group(5)
        val ext = m.group(6)
        // Use commit hash as version key to group all platform builds together
        (s"$version ($commit)", Some(formatTimestamp(timestamp)), DownloadFile(os, arch, ext, url, filename))
      }.orElse {
        // Try snapshot JAR (universal)
        SnapshotJarPattern.findFirstMatchIn(filename).map { m =>
          val version = s"${m.group(1)}-SNAPSHOT"
          val timestamp = m.group(2)
          val commit = m.group(3)
          val ext = m.group(4)
          (s"$version ($commit)", Some(formatTimestamp(timestamp)), DownloadFile("universal", "any", ext, url, filename))
        }
      }
    } else {
      // Try release with OS/arch first
      ReleasePattern.findFirstMatchIn(filename).map { m =>
        val version = m.group(1)
        val os = normalizeOs(m.group(2))
        val arch = m.group(3)
        val ext = m.group(4)
        (version, None, DownloadFile(os, arch, ext, url, filename))
      }.orElse {
        // Try release JAR (universal)
        ReleaseJarPattern.findFirstMatchIn(filename).map { m =>
          val version = m.group(1)
          val ext = m.group(2)
          (version, None, DownloadFile("universal", "any", ext, url, filename))
        }
      }
    }
  }

  private def normalizeOs(os: String): String = os match {
    case "linux" => "Linux"
    case "macos" | "osx" | "darwin" => "macOS"
    case "windows" | "win" => "Windows"
    case other => other.capitalize
  }

  private def formatTimestamp(ts: String): String = {
    // Convert "2025-07-18T115327" to "2025-07-18 11:53:27"
    try {
      val date = ts.take(10)
      val time = ts.drop(11)
      if (time.length >= 6) {
        s"$date ${time.take(2)}:${time.slice(2, 4)}:${time.slice(4, 6)}"
      } else {
        ts
      }
    } catch {
      case _: Exception => ts
    }
  }

  private def parseSemanticVersion(version: String): (Int, Int, Int, Int, String) = {
    // Parse versions like "1.4.0", "1.4.0-RC1", "1.3.0"
    val versionPattern = """(\d+)\.(\d+)\.(\d+)(?:-([A-Z]+)(\d*))?""".r
    version match {
      case versionPattern(major, minor, patch, prerelease, num) =>
        val prereleaseOrder = Option(prerelease) match {
          case None => 999 // Release versions come after pre-releases
          case Some("RC") => 100
          case Some("BETA") => 50
          case Some("ALPHA") => 25
          case _ => 0
        }
        val prereleaseNum = Option(num).filter(_.nonEmpty).map(_.toInt).getOrElse(0)
        (major.toInt, minor.toInt, patch.toInt, prereleaseOrder, prereleaseNum.toString)
      case _ =>
        (0, 0, 0, 0, version)
    }
  }
}
