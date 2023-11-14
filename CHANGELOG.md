# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## Changed

- Upgrade to Scala 2.13, Play 2.8, and sbt 1.7 with support for JDK 8 and 11 as well as experimental support for JDK 17
- Include sip-core with Groovy upgraded to 4.0 and support for JDK 8, 11 and 17 (for JDK 11+ run java with "--add-opens java.base/java.util=ALL-UNNAMED") 
- Replace library for calculating string similarity with an equivalent maintained library
- Don't display Narthex build's Git commit SHA as the plugin that provided this does not support current sbt versions  

- Remove millis from timestamp during incremental harvesting [[GH-165]](https://github.com/delving/narthex/pull/165)
- Add support for Dataset direct download for remote sync [[GH-166]](https://github.com/delving/narthex/pull/166)
- Add support for saving semaphore with maximum concurrency

- history of changes: see https://github.com/delving/narthex/compare/v0.6.8...master

## v0.6.8 (2022-10-13)

## Changed

- Add support for index tags to the narthex dataset [[GH-162]](https://github.com/delving/narthex/pull/162)
- update sip-core and version number [[GH-163]](https://github.com/delving/narthex/pull/163)
- Dataset metadata reduce required fields in the interface [[GH-164]](https://github.com/delving/narthex/pull/164)
 
- history of changes: see https://github.com/delving/narthex/compare/v0.6.7...v0.6.8

## v0.6.7 (2022-10-03)

## Changed

- always include `orgID` in the facts file [[GH-160]](https://github.com/delving/narthex/pull/160)
- include `type` next to `dataType` in the datasets interface [[GH-161]](https://github.com/delving/narthex/pull/161)

## Fixed

- fixed bug in OAI-PMH utf-8 harvesting encoding [[GH-159]](https://github.com/delving/narthex/pull/159)


- history of changes: see https://github.com/delving/narthex/compare/v0.6.6...v0.6.7

## v0.6.6 (2022-05-12)

## Added

- replace `oai_dc:dc` metadata root with `record` for specific datasets [[GH-158]](https://github.com/delving/narthex/pull/158)

## Changed

- history of changes: see https://github.com/delving/narthex/compare/v0.6.5...v0.6.6

## v0.6.5 (2022-03-07)

## Added

* bump version of sip-core [[GH-156]](https://github.com/delving/narthex/pull/156)

## Fixed

* upgrade build dependency for bootstrap webjars [[GH127]](https://github.com/delving/narthex/pull/157)
 
- history of changes: see https://github.com/delving/narthex/compare/v0.6.4...v0.6.5

## v0.6.4 (2022-01-06)

## Added

* bump version of sip-core [[GH-156]](https://github.com/delving/narthex/pull/156)
- history of changes: see https://github.com/delving/narthex/compare/v0.6.3...v0.6.4


## v0.6.3 (2022-01-03)

## Added

* Support for nant record definition [[GH-155]](https://github.com/delving/narthex/pull/155)

## v0.6.2 (2021-11-01)

## Fixed

* localID was not correctly extracted for NA /doc based URIS [[GH-154]](https://github.com/delving/narthex/pull/154)
 
- history of changes: see https://github.com/delving/narthex/compare/v0.6.1...v0.6.2

## v0.6.1 (2021-09-29)

### Added 

* Support for harvesting Anet.be/Brocade OAI-PMH endpoints [[GH-153]](https://github.com/delving/narthex/pull/153)

## v0.6.0 (2021-07-22)

### Added 

* Add dateset type and dataprovider URL to dataset metadata [[GH-152]](https://github.com/delving/narthex/pull/152)

### Fixed

* Update Facts in mapping when making sip-zip file [[GH-150]](https://github.com/delving/narthex/pull/150)
* Improve time to render of dataset list [[GH-151]](https://github.com/delving/narthex/pull/151)

### Changed

* Update to use sip-core v1.2.1

- history of changes: see https://github.com/delving/narthex/compare/v0.5.14...v0.6.0
