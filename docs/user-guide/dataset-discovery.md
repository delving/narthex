# Dataset Discovery

Dataset Discovery connects Narthex to OAI-PMH endpoints and lets you discover, verify, and bulk-import available datasets. It replaces the manual process of creating datasets one by one.

## Overview

The discovery workflow has four steps:

1. **Configure a source** - Add an OAI-PMH endpoint with harvest defaults and mapping rules
2. **Discover sets** - Query the endpoint to find all available sets
3. **Verify record counts** - Check which sets actually contain records (optional but recommended)
4. **Import selected sets** - Create Narthex datasets from the selected sets with one click

Navigate to Discovery via the sidebar menu.

![Discovery page overview](screenshots/discovery-overview.png)

## Managing Sources

Sources are OAI-PMH endpoints that Narthex can query for available datasets. The left panel shows all configured sources.

### Adding a Source

1. Click **+ New** in the sources panel header
2. Fill in the source configuration (see [Source Configuration Fields](#source-configuration-fields) below)
3. Click **Create Source**

![Add source modal](screenshots/discovery-source-modal.png)

### Editing a Source

Click the pencil icon next to a source name to open its configuration.

### Deleting a Source

Click the trash icon next to a source name. This removes the source configuration but does not affect any datasets already imported from it.

### Source Configuration Fields

The source configuration modal has four sections:

#### Basic Information

| Field | Description |
|-------|-------------|
| **Name** | Display name for this source (e.g., "Memorix Maior ENB") |
| **OAI-PMH URL** | Base URL for the endpoint. Narthex appends `?verb=ListSets` automatically |
| **Enabled** | Whether the source is active |

#### Harvest Defaults

These values are applied to all datasets imported from this source:

| Field | Description |
|-------|-------------|
| **Metadata Prefix** | OAI-PMH metadataPrefix for harvesting (e.g., `oai_dc`, `edm`) |
| **Schema Prefix** | Target schema prefix for mapping (e.g., `edm`, `miaou`) |
| **Aggregator** | Value for the EDM aggregator field |
| **EDM Type** | Default EDM type: IMAGE, TEXT, SOUND, VIDEO, or 3D |

#### Harvest Scheduling

| Field | Description |
|-------|-------------|
| **Schedule** | Automatic re-harvest interval (e.g., "Every 1 WEEK"). Leave empty to disable |
| **Incremental** | When enabled, only fetches records modified since the last harvest |

#### Mapping Rules

Mapping rules automatically assign a default mapping to imported datasets based on their setSpec. Each rule has:

| Field | Description |
|-------|-------------|
| **Regex Pattern** | Java/JavaScript regex matched against the normalized setSpec |
| **Prefix** | Schema prefix of the default mapping to assign |
| **Mapping** | Named mapping within that prefix |

**Examples:**
- `.*bidprentje$` matches specs ending with "bidprentje"
- `^enb-05-.*` matches specs starting with "enb-05-"

Use the **Test Pattern** button to verify your regex matches the expected specs before saving.

![Mapping rules configuration](screenshots/discovery-mapping-rules.png)

## Discovering Sets

1. Select a source from the left panel
2. Click **Discover Sets** in the panel header
3. Narthex queries the OAI-PMH endpoint's `ListSets` verb and categorizes the results

The summary bar shows totals:

```
42 total sets | 15 new | 20 existing | 5 empty | 2 ignored
```

![Discovery results with summary](screenshots/discovery-results.png)

### Set Categories

Results are grouped into four panels:

| Category | Description |
|----------|-------------|
| **New Sets** (green) | Sets not yet imported into Narthex. These can be selected for import |
| **Existing Datasets** (blue, collapsed) | Sets already imported. Read-only view |
| **Empty Sets** (gray, collapsed) | Sets verified to have 0 records. Can be ignored |
| **Ignored Sets** (orange, collapsed) | Sets you've chosen to skip. Can be unignored |

### New Sets Table

Each new set shows:

| Column | Description |
|--------|-------------|
| **Checkbox** | Select for import (click the row or the checkbox) |
| **Spec** | Normalized spec (top) and original setSpec (below, gray) |
| **Title** | Set title and description from the endpoint |
| **Records** | Record count (shown after verification, dash if unverified) |
| **Mapping** | Auto-assigned mapping from rules (if matched), or dash |
| **Menu** | Preview (opens OAI-PMH ListRecords in new tab) and Ignore options |

## Verifying Record Counts

Verification queries each set to check how many records it actually contains. This is optional but recommended because some endpoints advertise sets that are empty.

1. Click **Verify Record Counts** (or **Refresh Counts** if previously verified)
2. A progress bar shows verification status: `15 / 42` with a count of sets found with records
3. Verification runs in the background at 2 requests per second to avoid overloading the endpoint
4. When complete, the Records column updates with actual counts
5. Sets with 0 records move to the Empty Sets panel

![Verification progress bar](screenshots/discovery-verification.png)

Results are cached. The timestamp next to the refresh button shows when counts were last verified.

## Importing Datasets

1. Select the sets you want to import:
   - Click individual rows or checkboxes
   - Use **Select All** in the floating action bar at the bottom
   - Use **Clear** to deselect all
2. Click **Import Selected (N)** in the floating action bar

![Floating action bar with selection](screenshots/discovery-action-bar.png)

### Import Review

The import confirmation modal shows each selected set with editable fields:

| Field | Description |
|-------|-------------|
| **Dataset Name** | Pre-filled from the set title. Edit if needed |
| **Description** | Optional description |
| **Mapping** | Shows the auto-assigned mapping (from mapping rules) or "Manual mapping" |

![Import confirmation modal](screenshots/discovery-import-modal.png)

#### Auto-start Workflow

At the bottom of the import modal, the **Auto-start workflow** checkbox triggers the full processing pipeline for each imported dataset:

1. Harvest records from the OAI-PMH endpoint
2. Analyze the harvested data
3. Generate a SIP file
4. Process records through the mapping

The workflow stops before saving, allowing you to review results before publishing.

> **Note:** When importing many datasets (10+), a warning appears. The datasets are processed sequentially through the queue.

Click **Import N Dataset(s)** to proceed. Imported sets move from the New panel to the Existing panel.

## Managing Ignored Sets

### Ignoring Sets

You can ignore sets you don't want to import:
- **Single set**: Click the kebab menu (three dots) on a new set row and select **Ignore**
- **Bulk ignore**: Select multiple sets and click **Ignore (N)** in the floating action bar
- **Empty sets**: Expand the Empty Sets panel and click **Ignore** on individual sets

Ignored sets move to the Ignored Sets panel and won't appear in the New Sets list.

### Unignoring Sets

Expand the Ignored Sets panel and click **Unignore** on any set to move it back to the New Sets list.
