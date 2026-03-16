package organization

import akka.actor.{ActorLogging, Props}
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import services.WorkflowDatabase
import WorkflowEvent.{WorkflowStarted, StepStarted, StepProgress, StepCompleted, StepFailed, WorkflowCompleted, WorkflowCancelled, generateId}

class WorkflowPersistenceActor extends PersistentActor with ActorLogging {
  
  override def persistenceId: String = "workflow-persistence"
  
  private var state: WorkflowState = WorkflowState()
  
  override def receiveCommand: Receive = {
    case WorkflowPersistenceActor.Started(spec, trigger, steps) =>
      val workflowId = generateId()
      val event = WorkflowStarted(workflowId, spec, trigger, steps)
      persist(event) { persistedEvent =>
        state = state.addWorkflow(persistedEvent)
        workflowDb.insertWorkflow(workflowId, spec, trigger)
        workflowDb.insertStep(workflowId, steps.head)
      }
      sender() ! workflowId
      
    case WorkflowPersistenceActor.StepStart(workflowId, stepName, config) =>
      val event = StepStarted(workflowId, stepName, config)
      persist(event) { persistedEvent =>
        state = state.addStep(workflowId, stepName)
        workflowDb.insertStep(workflowId, stepName)
      }
      
    case WorkflowPersistenceActor.StepProgressMsg(workflowId, stepName, records, metadata) =>
      val event = StepProgress(workflowId, stepName, records, metadata)
      persist(event) { persistedEvent =>
        state = state.updateProgress(workflowId, stepName, records)
      }
      
    case WorkflowPersistenceActor.StepFinish(workflowId, stepName, duration, metadata) =>
      val event = StepCompleted(workflowId, stepName, duration, metadata)
      persist(event) { persistedEvent =>
        state = state.completeStep(workflowId, stepName, metadata)
        val metadataStr = metadata.map { case (k, v) => s"$k:$v" }.mkString(",")
        workflowDb.completeStep(workflowId, stepName, state.getRecordsProcessed(workflowId, stepName), duration, metadataStr)
      }
      
    case WorkflowPersistenceActor.StepError(workflowId, stepName, error, metadata) =>
      val event = StepFailed(workflowId, stepName, error, metadata)
      persist(event) { persistedEvent =>
        state = state.failStep(workflowId, stepName, error)
        workflowDb.failStep(workflowId, stepName, error)
      }
      
    case WorkflowPersistenceActor.Finished(workflowId) =>
      val event = WorkflowCompleted(workflowId)
      persist(event) { persistedEvent =>
        state = state.completeWorkflow(workflowId)
        workflowDb.completeWorkflow(workflowId, "completed", None)
      }
      
    case WorkflowPersistenceActor.Cancelled(workflowId) =>
      val event = WorkflowCancelled(workflowId)
      persist(event) { persistedEvent =>
        state = state.cancelWorkflow(workflowId)
        workflowDb.completeWorkflow(workflowId, "cancelled", None)
      }
      
    case WorkflowPersistenceActor.GetState =>
      sender() ! state
      
    case WorkflowPersistenceActor.GetWorkflow(workflowId) =>
      sender() ! state.getWorkflow(workflowId)
  }
  
  override def receiveRecover: Receive = {
    case event: WorkflowStarted =>
      state = state.addWorkflow(event)
      
    case event: StepStarted =>
      state = state.addStep(event.workflowId, event.stepName)
      
    case event: StepProgress =>
      state = state.updateProgress(event.workflowId, event.stepName, event.recordsProcessed)
      
    case event: StepCompleted =>
      state = state.completeStep(event.workflowId, event.stepName, event.metadata)
      
    case event: StepFailed =>
      state = state.failStep(event.workflowId, event.stepName, event.error)
      
    case event: WorkflowCompleted =>
      state = state.completeWorkflow(event.workflowId)
      
    case event: WorkflowCancelled =>
      state = state.cancelWorkflow(event.workflowId)
      
    case SnapshotOffer(_, snapshot: WorkflowState) =>
      state = snapshot
      
    case RecoveryCompleted =>
      log.info(s"Recovery completed, state: ${state.workflows.size} workflows")
  }
  
  private def workflowDb: WorkflowDatabase = {
    services.GlobalWorkflowDatabase.get()
  }
}

object WorkflowPersistenceActor {
  def props(): Props = Props(new WorkflowPersistenceActor())
  
  case class Started(spec: String, trigger: String, steps: List[String])
  case class StepStart(workflowId: String, stepName: String, config: Map[String, Any])
  case class StepProgressMsg(workflowId: String, stepName: String, recordsProcessed: Int, metadata: Map[String, Any])
  case class StepFinish(workflowId: String, stepName: String, duration: Long, metadata: Map[String, Any])
  case class StepError(workflowId: String, stepName: String, error: String, metadata: Map[String, Any])
  case class Finished(workflowId: String)
  case class Cancelled(workflowId: String)
  
  case object GetState
  case class GetWorkflow(workflowId: String)
}

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
    workflows.get(workflowId).map { w =>
      val updated = w.copy(currentStep = Some(stepName))
      copy(workflows = workflows + (workflowId -> updated))
    }.getOrElse(this)
  }
  
  def updateProgress(workflowId: String, stepName: String, records: Int): WorkflowState = {
    workflows.get(workflowId).map { w =>
      val updated = w.copy(recordsProcessed = records)
      copy(workflows = workflows + (workflowId -> updated))
    }.getOrElse(this)
  }
  
  def completeStep(workflowId: String, stepName: String, metadata: Map[String, Any]): WorkflowState = {
    workflows.get(workflowId).map { w =>
      val updated = w.copy(
        currentStep = None,
        completedSteps = w.completedSteps :+ CompletedStep(stepName, metadata)
      )
      copy(workflows = workflows + (workflowId -> updated))
    }.getOrElse(this)
  }
  
  def failStep(workflowId: String, stepName: String, error: String): WorkflowState = {
    workflows.get(workflowId).map { w =>
      val updated = w.copy(
        currentStep = None,
        status = "failed",
        errorMessage = Some(error),
        completedSteps = w.completedSteps :+ CompletedStep(stepName, Map("error" -> error))
      )
      copy(workflows = workflows + (workflowId -> updated))
    }.getOrElse(this)
  }
  
  def completeWorkflow(workflowId: String): WorkflowState = {
    workflows.get(workflowId).map { w =>
      val updated = w.copy(status = "completed")
      copy(workflows = workflows + (workflowId -> updated))
    }.getOrElse(this)
  }
  
  def cancelWorkflow(workflowId: String): WorkflowState = {
    workflows.get(workflowId).map { w =>
      val updated = w.copy(status = "cancelled")
      copy(workflows = workflows + (workflowId -> updated))
    }.getOrElse(this)
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
