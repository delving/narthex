# Narthex User Guide

Narthex is a cultural heritage data aggregation platform that harvests metadata from OAI-PMH endpoints, transforms it using configurable mappings, and publishes it to a triple store. This guide covers the features for discovering and onboarding new datasets at scale.

## Guides

- [Dataset Discovery](dataset-discovery.md) - Connect to OAI-PMH endpoints, discover available datasets, verify record counts, and bulk-import them
- [Default Mappings](default-mappings.md) - Manage organization-wide mapping templates with versioning, comparison, and auto-assignment during import

## Prerequisites

- Access to a running Narthex instance
- At least one OAI-PMH endpoint URL to connect to
- Mapping XML files for your target schema (e.g., EDM), or an existing dataset with a mapping you can copy
