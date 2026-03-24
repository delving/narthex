package services

import java.time.Instant

/** Thin wrapper around [[DatasetRepository]] providing the interface expected by
  * [[organization.WorkflowPersistenceActor]].
  *
  * Translates between the actor's lightweight call-site signatures and the
  * richer record types used by the repository.
  */
class WorkflowRepository(repo: DatasetRepository) {

  def insertWorkflow(id: String, spec: String, trigger: String): Unit = {
    repo.createWorkflow(WorkflowRecord(
      id = id,
      spec = spec,
      trigger = trigger,
      status = "running",
      startedAt = Instant.now()
    ))
  }

  def completeWorkflow(id: String, status: String, errorMessage: Option[String]): Unit = {
    repo.updateWorkflowStatus(
      id = id,
      status = status,
      errorMessage = errorMessage,
      completedAt = Some(Instant.now())
    )
  }

  def insertStep(workflowId: String, stepName: String): Int = {
    repo.createWorkflowStep(WorkflowStepRecord(
      workflowId = workflowId,
      stepName = stepName,
      status = "running",
      startedAt = Instant.now()
    ))
  }

  def completeStep(
      workflowId: String,
      stepName: String,
      recordsProcessed: Int,
      duration: Long,
      metadata: String
  ): Unit = {
    val steps = repo.getWorkflowSteps(workflowId)
    steps.find(s => s.stepName == stepName && s.status == "running").foreach { step =>
      val metadataJson = if (metadata.isEmpty) None else Some(s"""{"data":"$metadata","durationMs":$duration}""")
      repo.updateWorkflowStep(
        id = step.id.getOrElse(0),
        status = "completed",
        recordsProcessed = recordsProcessed,
        completedAt = Some(Instant.now()),
        metadata = metadataJson
      )
    }
  }

  def failStep(workflowId: String, stepName: String, errorMessage: String): Unit = {
    val steps = repo.getWorkflowSteps(workflowId)
    steps.find(s => s.stepName == stepName && s.status == "running").foreach { step =>
      repo.updateWorkflowStep(
        id = step.id.getOrElse(0),
        status = "failed",
        errorMessage = Some(errorMessage),
        completedAt = Some(Instant.now())
      )
    }
  }
}

object GlobalWorkflowRepository {
  @volatile private var instance: Option[WorkflowRepository] = None

  def set(repo: WorkflowRepository): Unit = {
    instance = Some(repo)
  }

  def get(): Option[WorkflowRepository] = instance

  def getOrThrow(): WorkflowRepository = instance.getOrElse(
    throw new IllegalStateException("WorkflowRepository not initialized — is PostgreSQL configured?")
  )

  def clear(): Unit = {
    instance = None
  }
}
