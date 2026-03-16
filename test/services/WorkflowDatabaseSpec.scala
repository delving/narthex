package services

import init.NarthexConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.{Configuration, Environment}
import java.io.File

class WorkflowDatabaseSpec extends AnyFlatSpec with Matchers {

  var db: WorkflowDatabase = _
  var tempDir: File = _

  "WorkflowDatabase" should "create tables on init" in {
    tempDir = new File(sys.props("java.io.tmpdir"), "narthex-test-" + System.currentTimeMillis())
    tempDir.mkdirs()

    val config = new NarthexConfig(Configuration.load(Environment.simple(),
      Map("narthexHome" -> tempDir.getAbsolutePath).asJava))

    db = new WorkflowDatabase(config)
    db.init()

    succeed
  }

  it should "insert and query workflow" in {
    db.insertWorkflow("test-workflow-1", "test-spec", "manual")

    val state = db.getDatasetState("test-spec")
    state should not be None
    state.get.spec should be("test-spec")
    state.get.currentWorkflowId should be("test-workflow-1")
  }

  it should "insert and retrieve workflow steps" in {
    db.insertStep("test-workflow-1", "harvest")

    val steps = db.getWorkflowSteps("test-workflow-1")
    steps should have size 1
    steps.head.stepName should be("harvest")
    steps.head.status should be("running")
  }

  it should "complete a workflow step" in {
    db.completeStep("test-workflow-1", "harvest", 100, 5000, """{"records": 100}""")

    val steps = db.getWorkflowSteps("test-workflow-1")
    steps should have size 1
    steps.head.status should be("completed")
    steps.head.recordsProcessed should be(100)
  }

  it should "complete a workflow" in {
    db.completeWorkflow("test-workflow-1", "completed", None)

    succeed
  }
}
