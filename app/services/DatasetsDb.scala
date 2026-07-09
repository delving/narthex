//===========================================================================
//    Copyright 2026 Delving B.V.
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

import java.io.File
import java.sql.{Connection, DriverManager}

import play.api.Logger

/**
 * Phase D2: the org-level dataset registry — replaces the per-dataset
 * Fuseki info graph entirely. Deliberately a prop-shaped KV store (not
 * columns): DsInfo's whole API is getLiteralProp/setSingularLiteralProps
 * over ~70 prop names, so a (spec, prop, value) table maps 1:1 with zero
 * per-prop schema churn, and the one-shot Fuseki migration is a straight
 * dump. The schema IS the contract (WAL; Go reads the same file).
 *
 * Autocommit stays ON (see RecordRegistry: a plain SELECT on an
 * autocommit-off connection freezes the snapshot); multi-statement writes
 * use the explicit tx helper.
 */
class DatasetsDb(orgRoot: File) {

  private val logger = Logger(getClass)

  orgRoot.mkdirs()
  private val dbFile = new File(orgRoot, "datasets.db")

  Class.forName("org.sqlite.JDBC")

  private val conn: Connection = {
    val c = DriverManager.getConnection(s"jdbc:sqlite:${dbFile.getAbsolutePath}")
    val s = c.createStatement()
    try {
      s.executeUpdate("PRAGMA journal_mode=WAL")
      s.executeUpdate("PRAGMA synchronous=NORMAL")
      s.executeUpdate("PRAGMA busy_timeout=5000")
      s.executeUpdate("""CREATE TABLE IF NOT EXISTS datasets (
        spec TEXT PRIMARY KEY,
        created_at TEXT NOT NULL
      )""")
      s.executeUpdate("""CREATE TABLE IF NOT EXISTS dataset_props (
        spec TEXT NOT NULL,
        prop TEXT NOT NULL,
        value TEXT NOT NULL,
        PRIMARY KEY (spec, prop)
      )""")
      s.executeUpdate("""CREATE TABLE IF NOT EXISTS dataset_prop_lists (
        spec TEXT NOT NULL,
        prop TEXT NOT NULL,
        value TEXT NOT NULL,
        PRIMARY KEY (spec, prop, value)
      )""")
    } finally s.close()
    c
  }

  private def tx[A](body: => A): A = synchronized {
    conn.setAutoCommit(false)
    try {
      val result = body
      conn.commit()
      result
    } catch {
      case e: Throwable =>
        try conn.rollback()
        catch { case re: Exception => logger.warn(s"rollback failed for $dbFile: ${re.getMessage}") }
        throw e
    } finally conn.setAutoCommit(true)
  }

  def createDataset(spec: String): Unit = synchronized {
    val ps = conn.prepareStatement(
      "INSERT OR IGNORE INTO datasets (spec, created_at) VALUES (?, ?)")
    try { ps.setString(1, spec); ps.setString(2, RecordRegistry.nowIso()); ps.executeUpdate() }
    finally ps.close()
  }

  def exists(spec: String): Boolean = synchronized {
    val ps = conn.prepareStatement("SELECT 1 FROM datasets WHERE spec = ?")
    try {
      ps.setString(1, spec)
      val rs = ps.executeQuery()
      try rs.next() finally rs.close()
    } finally ps.close()
  }

  def allSpecs(): Seq[String] = synchronized {
    val ps = conn.prepareStatement("SELECT spec FROM datasets ORDER BY spec")
    try {
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ArrayBuffer.empty[String]
        while (rs.next()) buf += rs.getString(1)
        buf.toSeq
      } finally rs.close()
    } finally ps.close()
  }

  def isEmpty: Boolean = synchronized {
    val ps = conn.prepareStatement("SELECT COUNT(*) FROM datasets")
    try {
      val rs = ps.executeQuery()
      try { rs.next(); rs.getInt(1) == 0 } finally rs.close()
    } finally ps.close()
  }

  def getProp(spec: String, prop: String): Option[String] = synchronized {
    val ps = conn.prepareStatement("SELECT value FROM dataset_props WHERE spec = ? AND prop = ?")
    try {
      ps.setString(1, spec); ps.setString(2, prop)
      val rs = ps.executeQuery()
      try { if (rs.next()) Option(rs.getString(1)) else None } finally rs.close()
    } finally ps.close()
  }

  /** All props of one dataset as a map. */
  def props(spec: String): Map[String, String] = synchronized {
    val ps = conn.prepareStatement("SELECT prop, value FROM dataset_props WHERE spec = ?")
    try {
      ps.setString(1, spec)
      val rs = ps.executeQuery()
      try {
        val b = Map.newBuilder[String, String]
        while (rs.next()) b += (rs.getString(1) -> rs.getString(2))
        b.result()
      } finally rs.close()
    } finally ps.close()
  }

  /** All props of ALL datasets — the dataset-list read (one query, pivoted). */
  def allProps(): Map[String, Map[String, String]] = synchronized {
    val ps = conn.prepareStatement("SELECT spec, prop, value FROM dataset_props")
    try {
      val rs = ps.executeQuery()
      try {
        val acc = scala.collection.mutable.Map.empty[String, scala.collection.mutable.Map[String, String]]
        while (rs.next()) {
          acc.getOrElseUpdate(rs.getString(1), scala.collection.mutable.Map.empty)
            .update(rs.getString(2), rs.getString(3))
        }
        // Datasets can exist with zero props
        allSpecsUnlocked().foreach(s => acc.getOrElseUpdate(s, scala.collection.mutable.Map.empty))
        acc.map { case (k, v) => k -> v.toMap }.toMap
      } finally rs.close()
    } finally ps.close()
  }

  private def allSpecsUnlocked(): Seq[String] = {
    val ps = conn.prepareStatement("SELECT spec FROM datasets")
    try {
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ArrayBuffer.empty[String]
        while (rs.next()) buf += rs.getString(1)
        buf.toSeq
      } finally rs.close()
    } finally ps.close()
  }

  def setProps(spec: String, pairs: (String, String)*): Unit = tx {
    createDatasetUnlocked(spec)
    val ps = conn.prepareStatement(
      """INSERT INTO dataset_props (spec, prop, value) VALUES (?, ?, ?)
         ON CONFLICT(spec, prop) DO UPDATE SET value = excluded.value""")
    try {
      pairs.foreach { case (prop, value) =>
        ps.setString(1, spec); ps.setString(2, prop); ps.setString(3, value)
        ps.addBatch()
      }
      ps.executeBatch()
    } finally ps.close()
  }

  private def createDatasetUnlocked(spec: String): Unit = {
    val ps = conn.prepareStatement(
      "INSERT OR IGNORE INTO datasets (spec, created_at) VALUES (?, ?)")
    try { ps.setString(1, spec); ps.setString(2, RecordRegistry.nowIso()); ps.executeUpdate() }
    finally ps.close()
  }

  def removeProp(spec: String, prop: String): Unit = synchronized {
    val ps = conn.prepareStatement("DELETE FROM dataset_props WHERE spec = ? AND prop = ?")
    try { ps.setString(1, spec); ps.setString(2, prop); ps.executeUpdate() }
    finally ps.close()
  }

  def listValues(spec: String, prop: String): List[String] = synchronized {
    val ps = conn.prepareStatement(
      "SELECT value FROM dataset_prop_lists WHERE spec = ? AND prop = ? ORDER BY value")
    try {
      ps.setString(1, spec); ps.setString(2, prop)
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ListBuffer.empty[String]
        while (rs.next()) buf += rs.getString(1)
        buf.toList
      } finally rs.close()
    } finally ps.close()
  }

  def addListValue(spec: String, prop: String, value: String): Unit = tx {
    createDatasetUnlocked(spec)
    val ps = conn.prepareStatement(
      "INSERT OR IGNORE INTO dataset_prop_lists (spec, prop, value) VALUES (?, ?, ?)")
    try { ps.setString(1, spec); ps.setString(2, prop); ps.setString(3, value); ps.executeUpdate() }
    finally ps.close()
  }

  def removeListValue(spec: String, prop: String, value: String): Unit = synchronized {
    val ps = conn.prepareStatement(
      "DELETE FROM dataset_prop_lists WHERE spec = ? AND prop = ? AND value = ?")
    try { ps.setString(1, spec); ps.setString(2, prop); ps.setString(3, value); ps.executeUpdate() }
    finally ps.close()
  }

  def deleteDataset(spec: String): Unit = tx {
    Seq("DELETE FROM dataset_props WHERE spec = ?",
        "DELETE FROM dataset_prop_lists WHERE spec = ?",
        "DELETE FROM datasets WHERE spec = ?").foreach { sql =>
      val ps = conn.prepareStatement(sql)
      try { ps.setString(1, spec); ps.executeUpdate() } finally ps.close()
    }
  }

  def close(): Unit = synchronized {
    scala.util.Try(conn.close())
    ()
  }
}
