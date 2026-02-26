# Default Mappings

Default Mappings are organization-wide mapping templates shared across datasets. They provide a central repository of versioned mapping definitions organized by schema prefix, so you can maintain a single mapping and apply it to many datasets.

## Overview

Default mappings are organized in a two-level hierarchy:

```
Schema Prefix (e.g., EDM)
  └── Named Mapping (e.g., "Museum Mapping")
        ├── Version a1b2c3d4 (current)
        ├── Version x9y8z7w6
        └── Version m3n4o5p6
```

- **Prefix** groups mappings by target schema (EDM, MIAOU, etc.)
- **Named mapping** is a specific mapping template within a prefix, with its own version history
- **Versions** are immutable snapshots of the mapping XML, identified by a short hash

Navigate to Default Mappings via the sidebar menu or the link in the dataset list header.

![Default mappings overview](screenshots/default-mappings-overview.png)

## Creating a Mapping

### First Mapping (with upload)

Use the collapsible **Create New Mapping** form at the top of the page:

1. Select a **Prefix** from the dropdown (e.g., EDM)
2. Enter a **Name** (e.g., "Museum Mapping")
3. Click **Choose file** and select a mapping XML file
4. Optionally add **Notes** describing this version
5. Click **Create & Upload**

This creates the named mapping and its first version in one step.

![Create new mapping form](screenshots/default-mappings-create.png)

### Additional Mappings Within a Prefix

To add another named mapping to an existing prefix:

1. Expand the prefix panel
2. At the bottom, find the **Add mapping** input
3. Enter the mapping name and click **Create**
4. Upload the first version using the upload controls in the expanded mapping

## Managing Versions

Each named mapping maintains a version history. Expand a prefix, then expand a named mapping to see its versions.

### Version Table

| Column | Description |
|--------|-------------|
| **Checkbox** | Select for comparison (max 2) |
| **Version** | Short hash identifier. The active version shows a green "Current" badge |
| **Timestamp** | When this version was created |
| **Source** | How the version was added: upload icon for manual upload, copy icon for copied from a dataset |
| **Notes** | Optional description added during upload |
| **Actions** | Preview, set as current, delete |

![Version table with actions](screenshots/default-mappings-versions.png)

### Uploading a New Version

Below the version table:

1. Click **Choose XML** and select a mapping file
2. Optionally add notes in the text field
3. Click **Upload**

The new version appears in the table. The first version is automatically set as current. Subsequent uploads are not - you must explicitly set them as current.

### Copying a Mapping from a Dataset

If a dataset already has a working mapping, you can copy it as a new version:

1. In the **Copy from** section (right side, below the version table)
2. Select a dataset from the dropdown
3. Click **Copy**

This extracts the mapping from the dataset's latest SIP file and adds it as a new version. The source dataset is recorded in the version metadata.

### Setting the Current Version

The current version is the one used when this default mapping is assigned to a dataset. To change it:

- Click the green checkmark button on any non-current version

### Previewing a Version

Click the eye icon to open a modal showing the formatted mapping XML.

### Deleting a Version

Click the trash icon to remove a version. You cannot delete the current version without first setting another version as current.

## Comparing Versions

To compare two versions side by side:

1. Select exactly 2 versions using the checkboxes in the version table
2. Click **Compare Selected**
3. A diff view opens showing line-by-line changes:
   - Green lines: added in the newer version
   - Red lines: removed from the older version
   - White lines: unchanged

![Version comparison diff view](screenshots/default-mappings-diff.png)

This is useful when a mapping has been updated and you want to verify what changed before setting it as current.

## Integration with Dataset Discovery

Default mappings connect to the Dataset Discovery feature through **mapping rules**. When configuring an OAI-PMH source (see [Dataset Discovery](dataset-discovery.md)), you can define rules that automatically assign a default mapping based on the dataset's spec name.

**Example workflow:**

1. Create a default mapping `edm/museum` with your standard museum mapping XML
2. In your OAI-PMH source configuration, add a mapping rule:
   - Pattern: `.*museum.*`
   - Prefix: `edm`
   - Mapping: `museum`
3. When you discover sets, any set whose spec matches `.*museum.*` will show the `edm/museum` mapping badge
4. On import, the mapping is automatically assigned to the new dataset

This eliminates the need to manually configure mappings for each dataset after import.

## Typical Workflow

1. **Set up default mappings** for your most common dataset types (e.g., `edm/museum`, `edm/archive`, `edm/photo`)
2. **Configure an OAI-PMH source** with mapping rules that match your dataset naming patterns
3. **Discover and import** datasets - mappings are assigned automatically
4. **Iterate**: When you improve a mapping on a specific dataset, copy it back to the default mapping as a new version, then apply it to other datasets

This keeps your mappings consistent across datasets while allowing controlled evolution through versioning.
