package services

import java.sql.{Connection, DriverManager}
import java.time.LocalDateTime
import javax.inject.{Inject, Singleton}
import play.api.Logger

import init.NarthexConfig

@Singleton
class WorkflowDatabase @Inject() (narthexConfig: NarthexConfig) {

  private val logger = Logger(getClass)
  private val dbPath = narthexConfig.narthexDataDir.getAbsolutePath + "/narthex.db"

  private val createTableStatements = List(
    """CREATE TABLE IF NOT EXISTS workflows (
      id TEXT PRIMARY KEY,
      spec TEXT NOT NULL,
      started_at TIMESTAMP,
      completed_at TIMESTAMP,
      status TEXT,
      trigger TEXT,
      error_message TEXT
    )""",
    """CREATE TABLE IF NOT EXISTS workflow_steps (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      workflow_id TEXT REFERENCES workflows(id),
      step_name TEXT NOT NULL,
      started_at TIMESTAMP,
      completed_at TIMESTAMP,
      status TEXT,
      records_processed INTEGER DEFAULT 0,
      error_message TEXT,
      metadata TEXT,
      FOREIGN KEY (workflow_id) REFERENCES workflows(id)
    )""",
    """CREATE TABLE IF NOT EXISTS dataset_state (
      spec TEXT PRIMARY KEY,
      current_workflow_id TEXT REFERENCES workflows(id),
      current_step TEXT,
      last_updated TIMESTAMP
    )""",
    "CREATE INDEX IF NOT EXISTS idx_workflows_spec ON workflows(spec)",
    "CREATE INDEX IF NOT EXISTS idx_workflow_steps_wfid ON workflow_steps(workflow_id)"
  )

  Class.forName("org.sqlite.JDBC")

  private val connection: Connection = {
    val conn = DriverManager.getConnection(s"jdbc:sqlite:$dbPath")
    conn.setAutoCommit(false)
    conn
  }

  def init(): Unit = synchronized {
    val stmt = connection.createStatement()
    try {
      createTableStatements.foreach(sql => stmt.executeUpdate(sql))
      connection.commit()
      logger.info("WorkflowDatabase initialized")
    } finally {
      stmt.close()
    }
  }

  def close(): Unit = synchronized {
    try {
      if (!connection.isClosed) {
        connection.close()
        logger.info("WorkflowDatabase connection closed")
      }
    } catch {
      case e: Exception =>
        logger.warn(s"Error closing WorkflowDatabase connection: ${e.getMessage}")
    }
  }

  def insertWorkflow(id: String, spec: String, trigger: String): Unit = synchronized {
    val sql = "INSERT INTO workflows (id, spec, started_at, status, trigger) VALUES (?, ?, ?, ?, ?)"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, id)
      stmt.setString(2, spec)
      stmt.setString(3, LocalDateTime.now().toString)
      stmt.setString(4, "running")
      stmt.setString(5, trigger)
      stmt.executeUpdate()
      connection.commit()
    } finally {
      stmt.close()
    }
  }

  def completeWorkflow(id: String, status: String, errorMessage: Option[String]): Unit = synchronized {
    val sql = "UPDATE workflows SET completed_at = ?, status = ?, error_message = ? WHERE id = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, LocalDateTime.now().toString)
      stmt.setString(2, status)
      stmt.setString(3, errorMessage.orNull)
      stmt.setString(4, id)
      stmt.executeUpdate()
      connection.commit()
    } finally {
      stmt.close()
    }
  }

  def insertStep(workflowId: String, stepName: String): Unit = synchronized {
    val sql = "INSERT INTO workflow_steps (workflow_id, step_name, started_at, status) VALUES (?, ?, ?, ?)"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, workflowId)
      stmt.setString(2, stepName)
      stmt.setString(3, LocalDateTime.now().toString)
      stmt.setString(4, "running")
      stmt.executeUpdate()
      connection.commit()
    } finally {
      stmt.close()
    }

    updateDatasetStateForStep(workflowId, stepName)
  }

  def completeStep(workflowId: String, stepName: String, recordsProcessed: Int, duration: Long, metadata: String): Unit = synchronized {
    val sql = "UPDATE workflow_steps SET completed_at = ?, status = ?, records_processed = ?, metadata = ? WHERE workflow_id = ? AND step_name = ? AND status = 'running'"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, LocalDateTime.now().toString)
      stmt.setString(2, "completed")
      stmt.setInt(3, recordsProcessed)
      stmt.setString(4, metadata)
      stmt.setString(5, workflowId)
      stmt.setString(6, stepName)
      stmt.executeUpdate()
      connection.commit()
    } finally {
      stmt.close()
    }
  }

  def failStep(workflowId: String, stepName: String, errorMessage: String): Unit = synchronized {
    val sql = "UPDATE workflow_steps SET completed_at = ?, status = ?, error_message = ? WHERE workflow_id = ? AND step_name = ? AND status = 'running'"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, LocalDateTime.now().toString)
      stmt.setString(2, "failed")
      stmt.setString(3, errorMessage)
      stmt.setString(4, workflowId)
      stmt.setString(5, stepName)
      stmt.executeUpdate()
      connection.commit()
    } finally {
      stmt.close()
    }
  }

  private def updateDatasetStateForStep(workflowId: String, step: String): Unit = {
    // Note: already inside synchronized from insertStep caller
    val sql = """
      INSERT INTO dataset_state (spec, current_workflow_id, current_step, last_updated)
      SELECT w.spec, ?, ?, ? FROM workflows w WHERE w.id = ?
      ON CONFLICT(spec) DO UPDATE SET current_workflow_id = ?, current_step = ?, last_updated = ?
    """
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, workflowId)
      stmt.setString(2, step)
      stmt.setString(3, LocalDateTime.now().toString)
      stmt.setString(4, workflowId)
      stmt.setString(5, workflowId)
      stmt.setString(6, step)
      stmt.setString(7, LocalDateTime.now().toString)
      stmt.executeUpdate()
      connection.commit()
    } finally {
      stmt.close()
    }
  }

  def getDatasetState(spec: String): Option[DatasetState] = synchronized {
    val sql = "SELECT spec, current_workflow_id, current_step, last_updated FROM dataset_state WHERE spec = ?"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, spec)
      val rs = stmt.executeQuery()
      try {
        if (rs.next()) {
          Some(DatasetState(
            spec = rs.getString("spec"),
            currentWorkflowId = rs.getString("current_workflow_id"),
            currentStep = rs.getString("current_step"),
            lastUpdated = rs.getString("last_updated")
          ))
        } else {
          None
        }
      } finally {
        rs.close()
      }
    } finally {
      stmt.close()
    }
  }

  def getWorkflowSteps(workflowId: String): List[WorkflowStep] = synchronized {
    val sql = "SELECT id, step_name, started_at, completed_at, status, records_processed, error_message, metadata FROM workflow_steps WHERE workflow_id = ? ORDER BY id"
    val stmt = connection.prepareStatement(sql)
    try {
      stmt.setString(1, workflowId)
      val rs = stmt.executeQuery()
      try {
        var steps = List[WorkflowStep]()
        while (rs.next()) {
          steps = steps :+ WorkflowStep(
            id = rs.getInt("id"),
            stepName = rs.getString("step_name"),
            startedAt = rs.getString("started_at"),
            completedAt = rs.getString("completed_at"),
            status = rs.getString("status"),
            recordsProcessed = rs.getInt("records_processed"),
            errorMessage = rs.getString("error_message"),
            metadata = rs.getString("metadata")
          )
        }
        steps
      } finally {
        rs.close()
      }
    } finally {
      stmt.close()
    }
  }
}

case class DatasetState(
  spec: String,
  currentWorkflowId: String,
  currentStep: String,
  lastUpdated: String
)

case class WorkflowStep(
  id: Int,
  stepName: String,
  startedAt: String,
  completedAt: String,
  status: String,
  recordsProcessed: Int,
  errorMessage: String,
  metadata: String
)

object GlobalWorkflowDatabase {
  @volatile private var instance: Option[WorkflowDatabase] = None
  
  def init(narthexConfig: NarthexConfig): Unit = {
    instance = Some(new WorkflowDatabase(narthexConfig))
    instance.foreach(_.init())
  }

  def close(): Unit = {
    instance.foreach(_.close())
    instance = None
  }
  
  def get(): WorkflowDatabase = instance.getOrElse {
    throw new RuntimeException("WorkflowDatabase not initialized")
  }
}
