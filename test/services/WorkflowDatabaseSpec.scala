package services

import init.NarthexConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import play.api.{Configuration, Environment}
import java.io.File
import org.apache.commons.io.FileUtils

class WorkflowDatabaseSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  var db: WorkflowDatabase = _
  var tempDir: File = _

  override def beforeEach(): Unit = {
    tempDir = new File(sys.props("java.io.tmpdir"), "narthex-test-" + System.currentTimeMillis())
    tempDir.mkdirs()

    val config = new NarthexConfig(Configuration.load(Environment.simple(),
      Map[String, AnyRef]("narthexHome" -> tempDir.getAbsolutePath)))

    db = new WorkflowDatabase(config)
    db.init()
  }

  override def afterEach(): Unit = {
    if (db != null) db.close()
    if (tempDir != null && tempDir.exists()) {
      FileUtils.deleteQuietly(tempDir)
    }
  }

  "WorkflowDatabase" should "create tables on init" in {
    // init already called in beforeEach - just verify no exception
    succeed
  }

  it should "insert workflow and populate dataset state via step" in {
    db.insertWorkflow("wf-1", "test-spec", "manual")

    // insertWorkflow alone does NOT populate dataset_state
    val stateBefore = db.getDatasetState("test-spec")
    stateBefore should be(None)

    // insertStep triggers updateDatasetStateForStep
    db.insertStep("wf-1", "harvest")

    val stateAfter = db.getDatasetState("test-spec")
    stateAfter should not be None
    stateAfter.get.spec should be("test-spec")
    stateAfter.get.currentWorkflowId should be("wf-1")
    stateAfter.get.currentStep should be("harvest")
  }

  it should "insert and retrieve workflow steps" in {
    db.insertWorkflow("wf-1", "test-spec", "manual")
    db.insertStep("wf-1", "harvest")

    val steps = db.getWorkflowSteps("wf-1")
    steps should have size 1
    steps.head.stepName should be("harvest")
    steps.head.status should be("running")
  }

  it should "complete a workflow step and update records" in {
    db.insertWorkflow("wf-1", "test-spec", "manual")
    db.insertStep("wf-1", "harvest")

    db.completeStep("wf-1", "harvest", 100, 5000, """{"records": 100}""")

    val steps = db.getWorkflowSteps("wf-1")
    steps should have size 1
    steps.head.status should be("completed")
    steps.head.recordsProcessed should be(100)
  }

  it should "fail a workflow step with error message" in {
    db.insertWorkflow("wf-1", "test-spec", "manual")
    db.insertStep("wf-1", "harvest")

    db.failStep("wf-1", "harvest", "Connection timeout")

    val steps = db.getWorkflowSteps("wf-1")
    steps should have size 1
    steps.head.status should be("failed")
    steps.head.errorMessage should be("Connection timeout")
  }

  it should "complete a workflow" in {
    db.insertWorkflow("wf-1", "test-spec", "manual")
    db.completeWorkflow("wf-1", "completed", None)

    // Verify workflow was completed (no exception = success)
    succeed
  }

  it should "complete a workflow with error message" in {
    db.insertWorkflow("wf-1", "test-spec", "manual")
    db.completeWorkflow("wf-1", "failed", Some("Something went wrong"))

    // Verify no exception
    succeed
  }

  it should "track dataset state through step transitions" in {
    db.insertWorkflow("wf-1", "test-spec", "manual")
    db.insertStep("wf-1", "harvest")

    val state1 = db.getDatasetState("test-spec")
    state1 should not be None
    state1.get.currentStep should be("harvest")
    state1.get.currentWorkflowId should be("wf-1")

    db.completeStep("wf-1", "harvest", 50, 3000, "")
    db.insertStep("wf-1", "process")

    val state2 = db.getDatasetState("test-spec")
    state2 should not be None
    state2.get.currentStep should be("process")
  }

  it should "handle multiple workflows for the same spec" in {
    db.insertWorkflow("wf-1", "test-spec", "manual")
    db.completeWorkflow("wf-1", "completed", None)

    db.insertWorkflow("wf-2", "test-spec", "automatic")
    db.insertStep("wf-2", "harvest")

    val state = db.getDatasetState("test-spec")
    state should not be None
    state.get.currentWorkflowId should be("wf-2")
  }

  it should "return None for unknown spec" in {
    val state = db.getDatasetState("nonexistent-spec")
    state should be(None)
  }

  it should "return empty list for unknown workflow steps" in {
    val steps = db.getWorkflowSteps("nonexistent-workflow")
    steps should be(empty)
  }
}
