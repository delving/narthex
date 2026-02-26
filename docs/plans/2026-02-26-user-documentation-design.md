# User Documentation Design

**Date:** 2026-02-26

## Goal

Create end-user documentation for the Dataset Discovery and Default Mappings features, targeted at technical operators who administer Narthex.

## Audience

Technical operators comfortable with web applications. They need clear workflows, field references, and screenshots but not hand-holding for basic web interactions.

## Scope

First batch covers two features:
1. **Dataset Discovery** - OAI-PMH source management, set discovery, record count verification, bulk import
2. **Default Mappings** - Organization-wide mapping templates with versioning, comparison, and dataset integration

## Format & Delivery

- Markdown files in `docs/user-guide/`
- Screenshot placeholders with descriptive alt text (real screenshots captured later from a clean instance)
- Shareable directly as markdown or exported to PDF for customer review

## Document Structure

```
docs/user-guide/
  index.md                    # Overview + navigation
  dataset-discovery.md        # Discovery workflow guide
  default-mappings.md         # Default mappings guide
  screenshots/                # Screenshot images (added later)
```

### index.md
- Brief Narthex overview (1 paragraph)
- Links to feature guides
- Prerequisites

### dataset-discovery.md
- Concepts: OAI-PMH sources, sets, discovery workflow
- Managing Sources: add/edit/delete, mapping rules
- Discovering Sets: results categories (new/existing/empty/ignored)
- Verifying Record Counts
- Importing Datasets: selection, review, auto-start workflow
- Managing Ignored Sets

### default-mappings.md
- Concepts: prefix/name hierarchy, versioning
- Creating a Mapping: upload flow
- Managing Versions: upload, copy from dataset, set current
- Comparing Versions: diff viewer
- Integration with Discovery: auto-assignment via mapping rules

## Tone

- Concise, task-oriented
- Each section self-contained
- English language

## Future Platform

Markdown is portable. When 5+ doc pages exist, evaluate wrapping with MkDocs Material or a lightweight Svelte doc site.
