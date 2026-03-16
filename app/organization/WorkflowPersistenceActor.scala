package organization

import akka.actor.{Actor, ActorLogging, Props}
import services.WorkflowDatabase
import WorkflowEvent.{WorkflowStarted, generateId}

class WorkflowPersistenceActor extends Actor with ActorLogging {
  
  private var state: WorkflowState = WorkflowState()
  
  override def receive: Receive = {
    case WorkflowPersistenceActor.Started(spec, trigger, steps) =>
      val workflowId = generateId()
      state = state.addWorkflow(event = WorkflowStarted(workflowId, spec, trigger, steps))
      workflowDb.insertWorkflow(workflowId, spec, trigger)
      workflowDb.insertStep(workflowId, steps.head)
      sender() ! workflowId
      
    case WorkflowPersistenceActor.StepStart(workflowId, stepName, _) =>
      state = state.addStep(workflowId, stepName)
      workflowDb.insertStep(workflowId, stepName)
      
    case WorkflowPersistenceActor.StepProgressMsg(workflowId, stepName, records, _) =>
      state = state.updateProgress(workflowId, stepName, records)
      
    case WorkflowPersistenceActor.StepFinish(workflowId, stepName, duration, metadata) =>
      state = state.completeStep(workflowId, stepName, metadata)
      val metadataStr = metadata.map { case (k, v) => s"$k:$v" }.mkString(",")
      workflowDb.completeStep(workflowId, stepName, state.getRecordsProcessed(workflowId, stepName), duration, metadataStr)
      
    case WorkflowPersistenceActor.StepError(workflowId, stepName, error, _) =>
      state = state.failStep(workflowId, stepName, error)
      workflowDb.failStep(workflowId, stepName, error)
      
    case WorkflowPersistenceActor.Finished(workflowId) =>
      state = state.completeWorkflow(workflowId)
      workflowDb.completeWorkflow(workflowId, "completed", None)
      
    case WorkflowPersistenceActor.Cancelled(workflowId) =>
      state = state.cancelWorkflow(workflowId)
      workflowDb.completeWorkflow(workflowId, "cancelled", None)
      
    case WorkflowPersistenceActor.GetState =>
      sender() ! state
      
    case WorkflowPersistenceActor.GetWorkflow(workflowId) =>
      sender() ! state.getWorkflow(workflowId)
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
