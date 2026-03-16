# Unified State Management Design

## Date: 2026-03-16

## Overview

Replace multiple ad-hoc state management mechanisms with a unified workflow-based system using Akka Persistence for event replay and SQLite for queryable state.

## Scope Consolidation

| Current Mechanism | New Approach |
|-------------------|--------------|
| OrgActor queue (JSON) | Akka Persistence events |
| DatasetActor FSM states | Workflow step events |
| Trends (JSONL) | SQLite tables |
| SetCountCache (JSON) | SQLite or keep |
| WebSocket state | Query SQLite |

## Architecture

```
┌─────────────────────────────────────────────┐
│           API / WebSocket                    │
│   Query current workflow state for UI        │
└─────────────────────┬───────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│       WorkflowStateActor (queryable)         │
│   - Current state of all workflows          │
│   - Handles WebSocket broadcasts            │
│   - Rebuilds from event log on restart       │
└─────────────────────┬───────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│        Akka Persistence (event log)          │
│   - Immutable events for replay              │
│   - Snapshots for speed                      │
└─────────────────────┬───────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│              SQLite (queryable)             │
│   - Current workflow state                  │
│   - Historical queries                       │
│   - WebSocket state source                   │
└─────────────────────────────────────────────┘
```

## SQLite Schema

```sql
-- Workflows: one row per workflow
CREATE TABLE workflows (
    id TEXT PRIMARY KEY,
    spec TEXT NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    status TEXT,
    trigger TEXT,
    error_message TEXT
);

-- Workflow steps: one row per step
CREATE TABLE workflow_steps (
    id INTEGER PRIMARY KEY,
    workflow_id TEXT REFERENCES workflows(id),
    step_name TEXT NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    status TEXT,
    records_processed INTEGER,
    error_message TEXT,
    metadata JSON
);

-- Dataset state: current state for quick queries
CREATE TABLE dataset_state (
    spec TEXT PRIMARY KEY,
    current_workflow_id TEXT REFERENCES workflows(id),
    current_step TEXT,
    last_updated TIMESTAMP
);
```

## Events

```scala
// Workflow events
case class WorkflowStarted(
  workflowId: String,
  spec: String,
  trigger: String,
  steps: List[String]
)

case class StepStarted(
  workflowId: String,
  stepName: String,
  config: Map[String, Any]
)

case class StepProgress(
  workflowId: String,
  stepName: String,
  recordsProcessed: Int,
  metadata: Map[String, Any]
)

case class StepCompleted(
  workflowId: String,
  stepName: String,
  duration: Long,
  metadata: Map[String, Any]
)

case class StepFailed(
  workflowId: String,
  stepName: String,
  error: String,
  metadata: Map[String, Any]
)

case class WorkflowCompleted(workflowId: String)
case class WorkflowCancelled(workflowId: String)
```

## Step Tracking

Steps to track:
- SampleHarvest
- Harvest
- Delimit
- Analyze
- Generate
- Process
- Save
- Skosify
- Categorize

At each transition, persist:
- Timestamp (start/end)
- Records processed
- Configuration used
- Duration
- Error messages if failed
- Fuseki mutations (what was mutated)

## Configuration

```hocon
# Akka Persistence
akka.persistence {
  journal.plugin = "akka.persistence.journal.file"
  journal.file.dir = "data/akka/journal"
  snapshot-store.plugin = "akka.persistence.snapshot.local"
  snapshot-store.file.dir = "data/akka/snapshots"
}

# SQLite
narthex.db {
  path = "data/narthex.db"
}
```

## Migration Priority

1. **OrgActor queue** → Akka Persistence (core reliability)
2. **DatasetActor states** → Workflow events + SQLite
3. **WebSocket** → Query SQLite instead of EventStream

## Benefits

- Unified event log for replay on restart
- Queryable workflow state via SQLite
- Replace multiple JSON/JSONL files
- Better introspection for users
- Cleaner activity monitor
- Foundation for future analytics
