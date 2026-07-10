# OpenWolf

@.wolf/OPENWOLF.md

This project uses OpenWolf for context management. Read and follow .wolf/OPENWOLF.md every session. Check .wolf/cerebrum.md before generating code. Check .wolf/anatomy.md before reading files.


# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview
Narthex is a Scala-based cultural heritage data aggregation and mapping platform built with Play Framework 2.8.20. It processes XML data, maps it to RDF/XML target schemas via SIP-Creator mappings, and pushes the processed records to Hub3 via its bulk API. All state lives on the filesystem and in embedded SQLite databases (no external triple store).

## Build and Development Commands

### Essential Commands
```bash
# Run tests
sbt test
# Run specific test
sbt "testOnly web.MainControllerSpec"

# Run application in development
sbt run
# or
make run

# Build distribution
sbt clean dist
# or
make dist

# Create Docker image
sbt docker:publishLocal

# Package application
sbt package
```

### Development Setup
1. Install prerequisites: `brew install sbt`
2. Initialize file system:
   ```bash
   mkdir -p ~/NarthexFiles/devorg/factory/edm
   cp docs/files/edm* ~/NarthexFiles/devorg/factory/edm
   ```
3. Run app: `sbt run` (available at http://localhost:9000)

No Fuseki or other external database is needed: the SQLite databases (org-level `datasets.db`, per-dataset `records.db`, and `queue.db`) are created automatically on first run.

## Architecture

### Actor-Based System
The application uses Akka actors for concurrent data processing. Key actor contexts:
- **DatasetContext**: Manages dataset lifecycle and processing coordination
- **HarvestContext**: Handles OAI-PMH harvesting operations
- **OrgContext**: Singleton managing organization-level operations

### Directory Structure
- `app/controllers/` - HTTP endpoints (MainController, AppController, ApiController)
- `app/dataset/` - Dataset processing actors and state management
- `app/harvest/` - OAI-PMH harvesting implementation
- `app/mapping/` - SIP mapping repositories (record definitions, default mappings)
- `app/record/` - XML record parsing and processing
- `app/triplestore/` - legacy RDF/SPARQL code, only used by the one-shot Fuseki migration
- `app/services/` - Core services (DatasetsDb, RecordRegistry, JobQueue, FileHandling, MailService, etc.)
- `app/analysis/` - Data analysis components

### Key Technologies
- **Dependency Injection**: Google Guice
- **Persistence**: embedded SQLite (org-level `datasets.db`, per-dataset `records.db`, `queue.db`) plus the filesystem
- **Testing**: ScalaTest with Mockito
- **Frontend**: AngularJS 1.3.17 (legacy)
- **WebSockets**: Real-time updates for dataset processing

### API Structure
- Main routes defined in `conf/routes`
- RESTful endpoints under `/narthex/app/`
- WebSocket endpoint: `/narthex/socket/dataset`
- API endpoints: `/narthex/api/` (with CSRF protection disabled via `+ nocsrf`)

### Configuration
- Main config: `conf/application.conf`
- Override with `-Dconfig.file=path/to/custom.conf`
- Key settings: organization ID, Hub3 bulk API URL (`naveApiUrl`) and auth token, mail server. The `triple-store` key is OPTIONAL and only used by the one-shot Fuseki->datasets.db migration.

### Data Flow
1. XML data uploaded or harvested via OAI-PMH
2. Analyzed and stored in tree structure on filesystem
3. Processed into RDF/XML records via the SIP-Creator mapping engine
4. Records registered in the per-dataset `records.db` and pushed to Hub3 via the bulk API
5. Dataset state/props/counts projected from SQLite (`datasets.db`) plus disk; available via API and downloadable as SIP files

## Important Files
- `app/services/DatasetsDb.scala` - org-level SQLite store for dataset props
- `app/services/RecordRegistry.scala` - per-dataset SQLite store for records/runs
- `app/services/FusekiMigration.scala` - one-shot Fuseki->datasets.db startup migration (legacy)
- `app/init/NarthexBindings.scala` - Dependency injection configuration
- `app/dataset/DatasetActor.scala` - Core dataset processing logic

## Testing Approach
Tests use ScalaTest with Play Framework support. Mock external services using Mockito and Play MockWS for web service testing.
- you need to use `make compile` to compile narthex

## Version Management
When bumping the version, **update both files**:
1. `version.sbt` - Canonical version for Scala/Play
2. `app/assets/javascripts/main.js` - Update `urlArgs: "v=X.X.X.X"` for JavaScript cache-busting

The `main.js` version must be a static string (not a variable) due to RequireJS r.js optimizer limitations. The optimizer parses the config and cannot evaluate dynamic expressions.
- use `make complile` for building. otherwise you get java build errors
- always use 'make compile' to compile. Never sbt directly.