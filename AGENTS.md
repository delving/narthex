# Repository Guidelines

## Project Structure & Module Organization
Primary Play code sits in `app/`, with `controllers/`, `services/`, and domain packages (`harvest/`, `mapping/`, `triplestore/`). Views live in `app/views/`, and web assets in `app/assets/` with compiled bundles in `public/`. Configuration (including `application.conf` and `routes`) is under `conf/`. Tests mirror the runtime packages inside `test/`; shared fixtures reside in `test/resources/`. Legacy Scala 2.11 sources are parked in `app-2.11/` and `test-2.11/` for compatibility fixes only.

## Build, Test, and Development Commands
Use sbt for the full lifecycle. `sbt compile` checks sources and regenerates routes. `sbt run` (or `make run`) launches the dev server on port 9000. Execute `sbt test` before every PR, and focus runs with `sbt "testOnly services.MailServiceSpec"`. `make dist` assembles the distributable ZIP, while `make package` builds the library JAR.

## Coding Style & Naming Conventions
Formatting is enforced through Scalafmt (`sbt scalafmtAll`). Stick to two-space indentation, meaningful package names, and Play’s dependency injection via `app/init` modules. Specs follow the existing patterns (`MailServiceSpec.scala`, `TestSourceRepo.scala`). Keep Scala filenames in PascalCase that mirrors the primary class or object.

## Testing Guidelines
ScalaTest with Mockito underpins the suite. Place new specs beside their production package and store JSON/RDF fixtures in `test/resources/`. Favour deterministic tests and keep FakeApplication setup inside individual specs. Run the full suite locally; treat failures as blockers.

## Commit & Pull Request Guidelines
Commits use Conventional Commits (`feat:`, `fix:`, `chore:`) with concise subject lines under 72 characters. Each PR should summarise the change, note tests executed or new fixtures added, and link the relevant ticket. Include screenshots or curl snippets when altering HTTP endpoints or UI behaviour. Confirm CI passes before requesting review.

## Security & Configuration Tips
Never commit secrets; reference environment overrides in `conf/overrides.conf` or deployment manifests. Review `docker-compose.yml` and the Docker entrypoint flags when modifying runtime configuration. Coordinate changes to triplestore schemas with the data team before editing `app/triplestore/` resources.
