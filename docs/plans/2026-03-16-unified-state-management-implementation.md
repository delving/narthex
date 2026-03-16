# Unified State Management Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement unified workflow-based state management with Akka Persistence for event replay and SQLite for queryable state, replacing multiple ad-hoc mechanisms.

**Architecture:** Use Akka Persistence for persistent event journal with automatic replay on restart. Use SQLite for current state queries and WebSocket state. Each DatasetActor workflow step emits events that are persisted and written to SQLite.

**Tech Stack:** Akka Persistence (file-based), SQLite (via JDBC), Play Framework

---

## Implementation Order

1. Add SQLite dependency and configuration
2. Create database schema and repository
3. Create workflow events
4. Create WorkflowPersistenceActor (event sourcing)
5. Integrate with DatasetActor to emit events
6. Remove old JSON queue persistence
7. Migrate WebSocket to query SQLite
8. Test end-to-end

---

### Task 1: Add SQLite Dependency

**Files:**
- Modify: `build.sbt`
- Modify: `conf/application.conf`

**Step 1: Add SQLite dependency to build.sbt**

Add to libraryDependencies in build.sbt:

```scala
libraryDependencies += "org.xerial" % "sqlite-jdbc" % "3.45.1.0"
```

**Step 2: Add configuration to application.conf**

Add to conf/application.conf:

```hocon
# SQLite configuration
narthex.db {
  path = "data/narthex.db"
}

# Akka Persistence
akka.persistence {
  journal {
    plugin = "akka.persistence.journal.file"
    file.dir = "data/akka/journal"
  }
  snapshot-store {
    plugin = "akka.persistence.snapshot.local"
    file.dir = "data/akka/snapshots"
  }
}
```

**Step 3: Commit**

```bash
git add build.sbt conf/application.conf
git commit -m "chore: add SQLite and Akka Persistence config"
```

---

### Task 2: Create Database Schema

**Files:**
- Create: `app/services/WorkflowDatabase.scala`
- Create: `test/services/WorkflowDatabaseSpec.scala`

**Step 1: Create WorkflowDatabase service**

Create app/services/WorkflowDatabase.scala:

```scala
package services

import java.sql.{Connection, DriverManager}
import java.time.LocalDateTime
import javax.inject.{Inject, Singleton}
import play.api.Logger

@Singleton
class WorkflowDatabase @Inject() (config: NarthexConfig) {
  
  private val logger = Logger(getClass)
  private val dbPath = config.narthexDataDir + "/narthex.db"
  
  private val createTablesSQL = """
    CREATE TABLE IF NOT EXISTS workflows (
      id TEXT PRIMARY KEY,
      spec TEXT NOT NULL,
      started_at TIMESTAMP,
      completed_at TIMESTAMP,
      status TEXT,
      trigger TEXT,
      error_message TEXT
    );
    
    CREATE TABLE IF NOT EXISTS workflow_steps (
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
    );
    
    CREATE TABLE IF NOT EXISTS dataset_state (
      spec TEXT PRIMARY KEY,
      current_workflow_id TEXT REFERENCES workflows(id),
      current_step TEXT,
      last_updated TIMESTAMP
    );
    
    CREATE INDEX IF NOT EXISTS idx_workflows_spec ON workflows(spec);
    CREATE INDEX IF NOT EXISTS idx_workflow_steps_wfid ON workflow_steps(workflow_id);
  """
  
  Class.forName("org.sqlite.JDBC")
  
  private val connection: Connection = {
    val conn = DriverManager.getConnection(s"jdbc:sqlite:$dbPath")
    conn.setAutoCommit(false)
    conn
  }
  
  def init(): Unit = {
    val stmt = connection.createStatement()
    stmt.executeUpdate(createTablesSQL)
    connection.commit()
    logger.info("WorkflowDatabase initialized")
  }
  
  // Workflow operations
  def insertWorkflow(id: String, spec: String, trigger: String): Unit = {
    val sql = "INSERT INTO workflows (id, spec, started_at, status, trigger) VALUES (?, ?, ?, ?, ?)"
    val stmt = connection.prepareStatement(sql)
    stmt.setString(1, id)
    stmt.setString(2, spec)
    stmt.setString(3, LocalDateTime.now().toString)
    stmt.setString(4, "running")
    stmt.setString(5, trigger)
    stmt.executeUpdate()
    connection.commit()
  }
  
  def completeWorkflow(id: String, status: String, errorMessage: Option[String]): Unit = {
    val sql = "UPDATE workflows SET completed_at = ?, status = ?, error_message = ? WHERE id = ?"
    val stmt = connection.prepareStatement(sql)
    stmt.setString(1, LocalDateTime.now().toString)
    stmt.setString(2, status)
    stmt.setString(3, errorMessage.orNull)
    stmt.setString(4, id)
    stmt.executeUpdate()
    connection.commit()
  }
  
  // Step operations
  def insertStep(workflowId: String, stepName: String): Unit = {
    val sql = "INSERT INTO workflow_steps (workflow_id, step_name, started_at, status) VALUES (?, ?, ?, ?)"
    val stmt = connection.prepareStatement(sql)
    stmt.setString(1, workflowId)
    stmt.setString(2, stepName)
    stmt.setString(3, LocalDateTime.now().toString)
    stmt.setString(4, "running")
    stmt.executeUpdate()
    connection.commit()
    
    // Update dataset_state
    updateDatasetStateForStep(workflowId, stepName)
  }
  
  def completeStep(workflowId: String, stepName: String, recordsProcessed: Int, duration: Long, metadata: String): Unit = {
    val sql = "UPDATE workflow_steps SET completed_at = ?, status = ?, records_processed = ?, metadata = ? WHERE workflow_id = ? AND step_name = ? AND status = 'running'"
    val stmt = connection.prepareStatement(sql)
    stmt.setString(1, LocalDateTime.now().toString)
    stmt.setString(2, "completed")
    stmt.setInt(3, recordsProcessed)
    stmt.setString(4, metadata)
    stmt.setString(5, workflowId)
    stmt.setString(6, stepName)
    stmt.executeUpdate()
    connection.commit()
  }
  
  def failStep(workflowId: String, stepName: String, errorMessage: String): Unit = {
    val sql = "UPDATE workflow_steps SET completed_at = ?, status = ?, error_message = ? WHERE workflow_id = ? AND step_name = ? AND status = 'running'"
    val stmt = connection.prepareStatement(sql)
    stmt.setString(1, LocalDateTime.now().toString)
    stmt.setString(2, "failed")
    stmt.setString(3, errorMessage)
    stmt.setString(4, workflowId)
    stmt.setString(5, stepName)
    stmt.executeUpdate()
    connection.commit()
  }
  
  private def updateDatasetStateForStep(workflowId: String, step: String): Unit = {
    val sql = """
      INSERT INTO dataset_state (spec, current_workflow_id, current_step, last_updated)
      SELECT w.spec, ?, ?, ? FROM workflows w WHERE w.id = ?
      ON CONFLICT(spec) DO UPDATE SET current_workflow_id = ?, current_step = ?, last_updated = ?
    """
    val stmt = connection.prepareStatement(sql)
    stmt.setString(1, workflowId)
    stmt.setString(2, step)
    stmt.setString(3, LocalDateTime.now().toString)
    stmt.setString(4, workflowId)
    stmt.setString(5, workflowId)
    stmt.setString(6, step)
    stmt.setString(7, LocalDateTime.now().toString)
    stmt.executeUpdate()
    connection.commit()
  }
  
  // Query operations
  def getDatasetState(spec: String): Option[DatasetState] = {
    val sql = "SELECT spec, current_workflow_id, current_step, last_updated FROM dataset_state WHERE spec = ?"
    val stmt = connection.prepareStatement(sql)
    stmt.setString(1, spec)
    val rs = stmt.executeQuery()
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
  }
  
  def getWorkflowSteps(workflowId: String): List[WorkflowStep] = {
    val sql = "SELECT id, step_name, started_at, completed_at, status, records_processed, error_message, metadata FROM workflow_steps WHERE workflow_id = ? ORDER BY id"
    val stmt = connection.prepareStatement(sql)
    stmt.setString(1, workflowId)
    val rs = stmt.executeQuery()
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
```

**Step 2: Run to verify compilation**

```bash
sbt compile
```

Expected: Compiles successfully

**Step 3: Commit**

```bash
git add app/services/WorkflowDatabase.scala
git commit -m "feat: add WorkflowDatabase service with SQLite schema"
```

---

### Task 3: Create Workflow Events

**Files:**
- Create: `app/organization/WorkflowEvent.scala`

**Step 1: Create WorkflowEvent case classes**

Create app/organization/WorkflowEvent.scala:

```scala
package organization

import java.util.UUID

object WorkflowEvent {
  
  def generateId(): String = UUID.randomUUID().toString
  
  sealed trait WorkflowEvent {
    val workflowId: String
    val timestamp: Long = System.currentTimeMillis()
  }
  
  case class WorkflowStarted(
    workflowId: String = generateId(),
    spec: String,
    trigger: String,
    steps: List[String]
  ) extends WorkflowEvent
  
  case class StepStarted(
    workflowId: String,
    stepName: String,
    config: Map[String, Any]
  ) extends WorkflowEvent
  
  case class StepProgress(
    workflowId: String,
    stepName: String,
    recordsProcessed: Int,
    metadata: Map[String, Any]
  ) extends WorkflowEvent
  
  case class StepCompleted(
    workflowId: String,
    stepName: String,
    duration: Long,
    metadata: Map[String, Any]
  ) extends WorkflowEvent
  
  case class StepFailed(
    workflowId: String,
    stepName: String,
    error: String,
    metadata: Map[String, Any]
  ) extends WorkflowEvent
  
  case class WorkflowCompleted(workflowId: String) extends WorkflowEvent
  
  case class WorkflowCancelled(workflowId: String) extends WorkflowEvent
  
  // For persistence
  val manifest: Map[Class[_], String] = Map(
    classOf[WorkflowStarted] -> "WorkflowStarted",
    classOf[StepStarted] -> "StepStarted",
    classOf[StepProgress] -> "StepProgress",
    classOf[StepCompleted] -> "StepCompleted",
    classOf[StepFailed] -> "StepFailed",
    classOf[WorkflowCompleted] -> "WorkflowCompleted",
    classOf[WorkflowCancelled] -> "WorkflowCancelled"
  )
}
```

**Step 2: Run to verify compilation**

```bash
sbt compile
```

Expected: Compiles successfully

**Step 3: Commit**

```bash
git add app/organization/WorkflowEvent.scala
git commit -m "feat: add workflow event case classes"
```

---

### Task 4: Create WorkflowPersistenceActor

**Files:**
- Create: `app/organization/WorkflowPersistenceActor.scala`

**Step 1: Create the persistent actor**

Create app/organization/WorkflowPersistenceActor.scala:

```scala
package organization

import akka.actor.{Actor, ActorLogging, Props}
import akka.persistence.{PersistentActor, SnapshotOffer}
import org.joda.time.DateTime
import play.api.libs.json.Json
import services.WorkflowDatabase
import WorkflowEvent._

class WorkflowPersistenceActor extends PersistentActor with ActorLogging {
  
  override def persistenceId: String = "workflow-persistence"
  
  private var state: WorkflowState = WorkflowState()
  
  val receiveCommand: Receive = {
    case Started(spec, trigger, steps) =>
      val workflowId = generateId()
      persist(WorkflowStarted(workflowId, spec, trigger, steps)) { event =>
        state = state.addWorkflow(event)
        workflowDb.insertWorkflow(event.workflowId, event.spec, event.trigger)
        
        // Also write to database
        workflowDb.insertStep(event.workflowId, steps.head)
      }
      sender() ! workflowId
      
    case StepStart(workflowId, stepName, config) =>
      persist(StepStarted(workflowId, stepName, config)) { event =>
        state = state.addStep(workflowId, stepName)
        workflowDb.insertStep(workflowId, stepName)
      }
      
    case StepProgressMsg(workflowId, stepName, records, metadata) =>
      persist(StepProgress(workflowId, stepName, records, metadata)) { event =>
        state = state.updateProgress(workflowId, stepName, records)
      }
      
    case StepFinish(workflowId, stepName, duration, metadata) =>
      persist(StepCompleted(workflowId, stepName, duration, metadata)) { event =>
        state = state.completeStep(workflowId, stepName, metadata)
        workflowDb.completeStep(workflowId, stepName, state.getRecordsProcessed(workflowId, stepName), duration, Json.stringify(Json.toJson(metadata)))
      }
      
    case StepError(workflowId, stepName, error, metadata) =>
      persist(StepFailed(workflowId, stepName, error, metadata)) { event =>
        state = state.failStep(workflowId, stepName, error)
        workflowDb.failStep(workflowId, stepName, error)
      }
      
    case Finished(workflowId) =>
      persist(WorkflowCompleted(workflowId)) { event =>
        state = state.completeWorkflow(workflowId)
        workflowDb.completeWorkflow(workflowId, "completed", None)
      }
      
    case Cancelled(workflowId) =>
      persist(WorkflowCancelled(workflowId)) { event =>
        state = state.cancelWorkflow(workflowId)
        workflowDb.completeWorkflow(workflowId, "cancelled", None)
      }
      
    case GetState =>
      sender() ! state
      
    case GetWorkflow(workflowId) =>
      sender() ! state.getWorkflow(workflowId)
  }
  
  val receiveRecover: Receive = {
    case event: WorkflowStarted =>
      state = state.addWorkflow(event)
      workflowDb.insertWorkflow(event.workflowId, event.spec, event.trigger)
      
    case event: StepStarted =>
      state = state.addStep(event.workflowId, event.stepName)
      workflowDb.insertStep(event.workflowId, event.stepName)
      
    case event: StepProgress =>
      state = state.updateProgress(event.workflowId, event.stepName, event.recordsProcessed)
      
    case event: StepCompleted =>
      state = state.completeStep(event.workflowId, event.stepName, event.metadata)
      workflowDb.completeStep(event.workflowId, event.stepName, state.getRecordsProcessed(event.workflowId, event.stepName), event.duration, Json.stringify(Json.toJson(event.metadata)))
      
    case event: StepFailed =>
      state = state.failStep(event.workflowId, event.stepName, event.error)
      workflowDb.failStep(event.workflowId, event.stepName, event.error)
      
    case event: WorkflowCompleted =>
      state = state.completeWorkflow(event.workflowId)
      workflowDb.completeWorkflow(event.workflowId, "completed", None)
      
    case event: WorkflowCancelled =>
      state = state.cancelWorkflow(event.workflowId)
      workflowDb.completeWorkflow(event.workflowId, "cancelled", None)
      
    case SnapshotOffer(_, snapshot: WorkflowState) =>
      state = snapshot
      
    case RecoveryCompleted =>
      log.info(s"Recovery completed, state: ${state.workflows.size} workflows")
  }
  
  // Snapshots every 100 events
  snapshotEvery(100)
  
  private def workflowDb: services.WorkflowDatabase = {
    services.GlobalWorkflowDatabase.get()
  }
}

object WorkflowPersistenceActor {
  def props(): Props = Props(new WorkflowPersistenceActor())
  
  // Commands
  case class Started(spec: String, trigger: String, steps: List[String])
  case class StepStart(workflowId: String, stepName: String, config: Map[String, Any])
  case class StepProgressMsg(workflowId: String, stepName: String, recordsProcessed: Int, metadata: Map[String, Any])
  case class StepFinish(workflowId: String, stepName: String, duration: Long, metadata: Map[String, Any])
  case class StepError(workflowId: String, stepName: String, error: String, metadata: Map[String, Any])
  case class Finished(workflowId: String)
  case class Cancelled(workflowId: String)
  
  // Queries
  case object GetState
  case class GetWorkflow(workflowId: String)
}

// State
case class WorkflowState(workflows: Map[String, Workflow] = Map.empty) {
  
  def addWorkflow(event: WorkflowStarted): WorkflowState = {
    val workflow = Workflow(
      id = event.workflowId,
      spec = event.spec,
      trigger = event.trigger,
      steps = event.steps,
      status = "running"
    )
    copy(workflows = workflows + (event.workflowId -> workflow))
  }
  
  def addStep(workflowId: String, stepName: String): WorkflowState = {
    val workflow = workflows.get(workflowId).map { w =>
      w.copy(currentStep = Some(stepName))
    }
    copy(workflows = workflows + (workflowId -> workflow.get))
  }
  
  def updateProgress(workflowId: String, stepName: String, records: Int): WorkflowState = {
    val workflow = workflows.get(workflowId).map { w =>
      w.copy(recordsProcessed = records)
    }
    copy(workflows = workflows + (workflowId -> workflow.get))
  }
  
  def completeStep(workflowId: String, stepName: String, metadata: Map[String, Any]): WorkflowState = {
    val workflow = workflows.get(workflowId).map { w =>
      w.copy(
        currentStep = None,
        completedSteps = w.completedSteps :+ CompletedStep(stepName, metadata)
      )
    }
    copy(workflows = workflows + (workflowId -> workflow.get))
  }
  
  def failStep(workflowId: String, stepName: String, error: String): WorkflowState = {
    val workflow = workflows.get(workflowId).map { w =>
      w.copy(
        currentStep = None,
        status = "failed",
        errorMessage = Some(error),
        completedSteps = w.completedSteps :+ CompletedStep(stepName, Map("error" -> error))
      )
    }
    copy(workflows = workflows + (workflowId -> workflow.get))
  }
  
  def completeWorkflow(workflowId: String): WorkflowState = {
    val workflow = workflows.get(workflowId).map { w =>
      w.copy(status = "completed")
    }
    copy(workflows = workflows + (workflowId -> workflow.get))
  }
  
  def cancelWorkflow(workflowId: String): WorkflowState = {
    val workflow = workflows.get(workflowId).map { w =>
      w.copy(status = "cancelled")
    }
    copy(workflows = workflows + (workflowId -> workflow.get))
  }
  
  def getWorkflow(workflowId: String): Option[Workflow] = workflows.get(workflowId)
  
  def getRecordsProcessed(workflowId: String, stepName: String): Int = {
    workflows.get(workflowId).map(_.recordsProcessed).getOrElse(0)
  }
}

case class Workflow(
  id: String,
  spec: String,
  trigger: String,
  steps: List[String],
  currentStep: Option[String] = None,
  status: String = "running",
  recordsProcessed: Int = 0,
  completedSteps: List[CompletedStep] = Nil,
  errorMessage: Option[String] = None
)

case class CompletedStep(
  name: String,
  metadata: Map[String, Any]
)
```

**Step 2: Create global database singleton**

Add to services/WorkflowDatabase.scala:

```scala
package services

import javax.inject.Inject

object GlobalWorkflowDatabase {
  @volatile private var instance: Option[WorkflowDatabase] = None
  
  def init(config: NarthexConfig): Unit = {
    instance = Some(new WorkflowDatabase(config))
    instance.foreach(_.init())
  }
  
  def get(): WorkflowDatabase = instance.getOrElse {
    throw new RuntimeException("WorkflowDatabase not initialized")
  }
}
```

**Step 3: Run to verify compilation**

```bash
sbt compile
```

Expected: Compiles successfully

**Step 4: Commit**

```bash
git add app/organization/WorkflowPersistenceActor.scala
git commit -m "feat: add WorkflowPersistenceActor with event sourcing"
```

---

### Task 5: Initialize Database on Startup

**Files:**
- Modify: `app/init/NarthexModule.scala` (or wherever application starts)

**Step 1: Find where to initialize**

Search for where NarthexConfig is created:

```bash
grep -r "NarthexConfig" app/ --include="*.scala" | head -20
```

**Step 2: Add initialization**

Add to application startup:

```scala
import services.GlobalWorkflowDatabase

// In your application startup (e.g., Global object or startup hook)
GlobalWorkflowDatabase.init(narthexConfig)
```

**Step 3: Commit**

```bash
git add app/init/NarthexModule.scala  # or appropriate file
git commit -m "feat: initialize WorkflowDatabase on startup"
```

---

### Task 6: Integrate DatasetActor with Workflow Events

**Files:**
- Modify: `app/dataset/DatasetActor.scala`

**Step 1: Modify DatasetActor to emit events**

Add to DatasetActor:

```scala
import organization.WorkflowPersistenceActor._
import organization.OrgContext

// Add to class properties
var currentWorkflowId: Option[String] = None
var workflowStartTime: Option[Long] = None
var currentStepName: Option[String] = None

// In startWorkflow or first state transition:
val workflowId = orgContext.workflowActorRef ! Started(
  spec = spec,
  trigger = trigger,
  steps = List("Harvesting", "Analyzing", "Generating", "Processing", "Saving", "Skosifying", "Categorizing")
)
currentWorkflowId = Some(workflowId)
workflowStartTime = Some(System.currentTimeMillis())

// At each state transition (e.g., when going from Harvesting to Analyzing):
currentWorkflowId.foreach { wfId =>
  val stepName = "Harvesting" // or derive from state
  orgContext.workflowActorRef ! StepFinish(wfId, stepName, duration, Map(
    "recordsProcessed" -> recordsProcessed,
    "fusekiMutations" -> List("...")
  ))
  orgContext.workflowActorRef ! StepStart(wfId, "Analyzing", Map("config" -> "..."))
}

// On error:
currentWorkflowId.foreach { wfId =>
  currentStepName.foreach { stepName =>
    orgContext.workflowActorRef ! StepError(wfId, stepName, errorMessage, Map.empty)
  }
}
```

**Step 2: Run to verify compilation**

```bash
sbt compile
```

Expected: Compiles successfully

**Step 3: Commit**

```bash
git add app/dataset/DatasetActor.scala
git commit -m "feat: integrate DatasetActor with workflow events"
```

---

### Task 7: Create Workflow Actor in OrgContext

**Files:**
- Modify: `app/organization/OrgContext.scala`

**Step 1: Add workflow actor**

Add to OrgContext:

```scala
lazy val workflowActorInst = new WorkflowPersistenceActor()

lazy val workflowActorRef: ActorRef = actorSystem.actorOf(
  Props(workflowActorInst), 
  s"${narthexConfig.orgId}-workflow"
)

def workflowActor: ActorRef = workflowActorRef
```

**Step 2: Run to verify compilation**

```bash
sbt compile
```

Expected: Compiles successfully

**Step 3: Commit**

```bash
git add app/organization/OrgContext.scala
git commit -m "feat: add workflow actor to OrgContext"
```

---

### Task 8: Remove Old JSON Queue Persistence

**Files:**
- Modify: `app/organization/OrgActor.scala`

**Step 1: Remove JSON persistence code**

Remove from OrgActor:
- `queueStateFile` field
- `saveQueueState()` method
- `loadPersistedQueueState()` method
- `postStop()` persistence call
- All JSON-related imports and case classes

Keep the in-memory queue for current operations - it will be reconstructed from Akka Persistence on restart.

**Step 2: Run to verify compilation**

```bash
sbt compile
```

Expected: Compiles successfully

**Step 3: Commit**

```bash
git add app/organization/OrgActor.scala
git commit -m "refactor: remove JSON queue persistence, rely on Akka Persistence"
```

---

### Task 9: Migrate WebSocket to Query SQLite

**Files:**
- Modify: `app/controllers/WebSocketController.scala`
- Modify: `app/organization/WebSocketBroadcast.scala`

**Step 1: Query SQLite instead of EventStream**

Replace EventStream-based broadcasts with SQLite queries:

```scala
def getDatasetStatus(spec: String): Future[DatasetStatus] = Future {
  val state = workflowDb.getDatasetState(spec)
  state.map { s =>
    DatasetStatus(
      spec = s.spec,
      workflowId = s.currentWorkflowId,
      currentStep = s.currentStep,
      lastUpdated = s.lastUpdated
    )
  }
}
```

**Step 2: Run to verify compilation**

```bash
sbt compile
```

Expected: Compiles successfully

**Step 3: Commit**

```bash
git add app/controllers/WebSocketController.scala
git commit -m "refactor: migrate WebSocket to query SQLite"
```

---

### Task 10: Integration Test

**Files:**
- Create: `test/services/WorkflowPersistenceActorSpec.scala`

**Step 1: Write integration test**

```scala
package services

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import organization.WorkflowPersistenceActor._

class WorkflowPersistenceActorSpec extends TestKit(ActorSystem("test")) 
  with WordSpecLike with Matchers with BeforeAndAfterAll {
  
  override def afterAll(): Unit = {
    shutdown(system)
  }
  
  "WorkflowPersistenceActor" must {
    "persist and recover workflow" in {
      val actor = system.actorOf(Props[WorkflowPersistenceActor]())
      val probe = TestProbe(system)
      
      // Start workflow
      actor ! Started("test-spec", "manual", List("Harvesting", "Analyzing"))
      val workflowId = expectMsgType[String]
      
      // Start step
      actor ! StepStart(workflowId, "Harvesting", Map("source" -> "oai"))
      
      // Progress
      actor ! StepProgressMsg(workflowId, "Harvesting", 100, Map.empty)
      
      // Complete step
      actor ! StepFinish(workflowId, "Harvesting", 5000L, Map("records" -> 100))
      
      // Complete workflow
      actor ! Finished(workflowId)
      
      // Query state
      actor ! GetWorkflow(workflowId)
      val workflow = expectMsgType[Option[organization.Workflow]]
      
      workflow shouldBe defined
      workflow.get.status shouldBe "completed"
      workflow.get.completedSteps.size shouldBe 1
    }
  }
}
```

**Step 2: Run test**

```bash
sbt "testOnly services.WorkflowPersistenceActorSpec"
```

Expected: Test passes

**Step 3: Commit**

```bash
git add test/services/WorkflowPersistenceActorSpec.scala
git commit -m "test: add WorkflowPersistenceActor integration test"
```

---

## Summary

| Task | Description |
|------|-------------|
| 1 | Add SQLite dependency and configuration |
| 2 | Create database schema and repository |
| 3 | Create workflow events |
| 4 | Create WorkflowPersistenceActor |
| 5 | Initialize database on startup |
| 6 | Integrate DatasetActor with workflow events |
| 7 | Add workflow actor to OrgContext |
| 8 | Remove old JSON queue persistence |
| 9 | Migrate WebSocket to query SQLite |
| 10 | Integration test |
