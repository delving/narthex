# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview
Narthex is a Scala-based cultural heritage data aggregation and mapping platform built with Play Framework 2.8.20. It processes XML data, manages SKOS vocabulary mappings, and integrates with RDF triple stores for museum and archive data management.

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
2. Start Fuseki triple store:
   ```bash
   cd [fuseki_homedir]
   ./fuseki-server --config=[/full/path_to_narthex]/fuseki.ttl
   ```
3. Initialize file system:
   ```bash
   mkdir -p ~/NarthexFiles/devorg/factory/edm
   cp docs/files/edm* ~/NarthexFiles/devorg/factory/edm
   ```
4. Run app: `sbt run` (available at http://localhost:9000)

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
- `app/mapping/` - SKOS vocabulary mapping functionality
- `app/record/` - XML record parsing and processing
- `app/triplestore/` - RDF/SPARQL integration with Fuseki
- `app/services/` - Core services (FileHandling, MailService, etc.)
- `app/analysis/` - Data analysis components

### Key Technologies
- **Dependency Injection**: Google Guice
- **Triple Store**: Apache Fuseki for RDF persistence
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
- Key settings: triple store URL, organization ID, auth tokens, mail server

### Data Flow
1. XML data uploaded or harvested via OAI-PMH
2. Analyzed and stored in tree structure on filesystem
3. Processed into RDF triples
4. Stored in Fuseki triple store
5. Available via API and downloadable as SIP files

## Important Files
- `app/triplestore/GraphProperties.scala` - All URIs used by Narthex
- `app/triplestore/Sparql.scala` - All SPARQL queries
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