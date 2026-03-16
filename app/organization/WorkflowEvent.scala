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
}
