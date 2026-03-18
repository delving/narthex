# Repository Guidelines

## Project Structure & Module Organization
- Primary Play code lives in `app/` with `controllers/`, `services/`, and domain packages (`harvest/`, `mapping/`, `triplestore/`).
- Views reside in `app/views/`; client assets are in `app/assets/` and compile to `public/`.
- Configuration and routing stay under `conf/` (notably `application.conf` and `routes`).
- Tests mirror runtime packages in `test/`; shared fixtures belong in `test/resources/`.
- Legacy Scala 2.11 compatibility work is isolated to `app-2.11/` and `test-2.11/`.
- Deployment and tooling scripts live in `deploy/`, `deployment/`, and `scripts/`; packaging targets are in the `Makefile`.

## Build, Test, and Development Commands
- `sbt compile` — compiles sources and regenerates routes.
- `sbt run` or `make run` — starts the dev server on port 9000.
- `sbt test` — runs the full suite; use `sbt "testOnly services.MailServiceSpec"` for focused specs.
- `make dist` — assembles the distributable ZIP; `make package` — builds the library JAR.
- `sbt scalafmtAll` — enforces formatting across Scala sources.

## Coding Style & Naming Conventions
- Use Scalafmt with two-space indentation; keep Scala filenames in PascalCase matching the primary class or object.
- Prefer meaningful package names and Play dependency injection via modules in `app/init`.
- Mirror existing spec naming patterns such as `MailServiceSpec.scala` and `TestSourceRepo.scala`.
- Avoid committing secrets; rely on `conf/overrides.conf` or deployment manifests for environment-specific settings.

## Testing Guidelines
- ScalaTest with Mockito is standard; keep suites deterministic and isolated.
- Place specs beside their production packages; store JSON/RDF fixtures in `test/resources/`.
- Configure FakeApplication within each spec rather than globally to avoid shared state.
- Run targeted suites during development and the full `sbt test` before opening PRs.

## Commit & Pull Request Guidelines
- Use Conventional Commits (e.g., `feat:`, `fix:`, `chore:`) with subjects under 72 characters.
- PRs should summarise changes, list tests run or fixtures added, and link the relevant ticket.
- Include screenshots or curl snippets for HTTP endpoint or UI changes when helpful.
- Ensure CI is green before requesting review; coordinate schema updates with the data team when altering `app/triplestore/` resources.
