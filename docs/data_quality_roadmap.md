# Data Quality Analysis Roadmap

This document captures the roadmap for data quality analysis features in Narthex.

## Completed Features

### Phase 1: Basic Quality Statistics (Completed)
- **Completeness**: Percentage of records with at least one value for a field
- **Empty count**: Number of empty/blank values
- **Avg per record**: Average number of values per record (cardinality indicator)
- **Records with value**: Count of records containing the field

### Phase 2: Type Detection (Completed)
- **Automatic type detection**: TEXT, INTEGER, DECIMAL, DATE, URL, EMAIL, IDENTIFIER, BOOLEAN
- **Type consistency**: Percentage of values matching the dominant type
- **Mixed type detection**: Flag fields with multiple data types
- **Type distribution**: Breakdown of types when mixed

### Phase 3: Value Statistics (Completed)
- **Length statistics**: Min/max/avg character length per value
- **Word count**: Min/max/avg words per value
- **Numeric range**: Min/max for numeric fields
- **Date/year range**: Min/max year extraction from date values
- **Whitespace issues**: Detection of leading, trailing, and multiple spaces

### Phase 3b: Pattern Analysis & URI Validation (Completed)
- **Pattern detection**: Automatic detection of identifier patterns
  - UUID: `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`
  - URN: `urn:namespace:identifier`
  - PREFIX_ID: `ABC-123`, `PREF_456`
  - HTTP_URI, HTTPS_URI, FTP_URI
- **Pattern consistency**: Percentage of values matching the dominant pattern
- **Pattern distribution**: Breakdown when multiple patterns detected
- **URI validation**: Syntax validation using Java's URI class
  - Valid/invalid counts for all URI-like values
  - Validation rate percentage

### Phase 4: Quality Indicators in Tree View (Completed)
- **Warning icon**: Yellow triangle (⚠) next to fields with quality issues
- **Hover tooltip**: Shows summary of detected issues
- **Color-coded completeness badge**: Green (≥90%), Yellow (≥50%), Red (<50%)
- **Issues detected in tree**:
  - Low completeness (< 50%)
  - High empty rate (> 10%)
- **Quality Tab Issues Alert Box**:
  - Yellow alert box at top of Quality tab when issues exist
  - Lists all issues with clear explanations
  - Includes: low completeness, high empty rate, mixed types, whitespace issues, invalid URIs

### Phase 5: Quality Summary Dashboard (Completed)
- **Quality Summary button**: In dataset analysis view header
- **Modal overlay**: Shows aggregated quality metrics for entire dataset
- **Overall Quality Score**: Weighted composite score (0-100%)
  - Completeness: 40%, Type consistency: 25%, No issues: 20%, Value presence: 15%
- **Summary cards** (row 1): Overall score, fields with values, fields with issues
- **Record statistics** (row 2):
  - Fields in every record (100% completeness) - clickable to expand list
  - Avg fields per record (total field values / records)
  - Avg unique fields per record (unique field presence / records)
- **Fields in every record list**: Expandable table showing all fields with 100% completeness
  - Field name and path (clickable to navigate)
  - Average occurrences per record (highlights fields with >1 avg)
- **Completeness distribution**: Excellent/Good/Fair/Poor breakdown
- **Uniqueness distribution**: Shows value uniqueness across fields
  - Identifiers (100%): Likely unique identifier fields
  - High (80-99%): Mostly unique values
  - Medium (20-79%): Mixed uniqueness
  - Low (<20%): Controlled vocabularies or repeated values
  - Average uniqueness percentage displayed in header
- **Issues by type table**: Low completeness, high empty rate, mixed types, invalid URIs, whitespace issues
- **Fields with issues**: Expandable list showing all problematic fields
  - Shows top 10 by default, "Show All" button to expand
  - Sorted by issue count (most issues first), then by completeness
  - Clickable to navigate to field in tree view
- **Navigation**: Click field to jump to it in tree view
- **Source/Processed support**: Works with both analysis types

### Phase 7: Export Quality Report (Completed)
- **Export dropdown**: In Quality Summary modal footer
- **CSV export**: Comprehensive report with sections for:
  - Summary statistics (overall score, field counts, record counts)
  - Completeness distribution
  - Uniqueness distribution
  - Issues by type
  - Fields in every record (100% completeness)
  - Identifier fields (100% unique)
  - All fields with quality metrics (path, tag, completeness, uniqueness, issues)
- **JSON export**: Full structured data for programmatic use
- **Source/Processed support**: Exports respect current analysis type
- **Direct download**: Files download with appropriate filenames

### Phase 6: Data Quality Score (Completed)
- **Per-field quality scores**: Composite score (0-100) calculated for each field
- **Weighted scoring components**:
  - Completeness: 40%
  - Type consistency: 25%
  - No whitespace issues: 15%
  - Reasonable value lengths: 10%
  - No empty values: 10%
- **Score categories**: Excellent (≥90), Good (70-89), Fair (50-69), Poor (<50)
- **Configurable display**: `narthex.quality.showFieldScores` config option
- **Quality Score Distribution panel**: Shows count of fields in each category
- **All Fields with Scores panel**: Collapsible list of all fields sorted by score
- **Score column in issues table**: Color-coded score labels in problematic fields
- **CSV export**: Includes score distribution and per-field scores when enabled

### Phase 8: Source vs Processed Comparison (Completed - Tentative)
*Note: This feature is tentative pending user feedback on its usefulness.*

- **Comparison tab**: Added to Quality Summary modal alongside Source/Processed tab
- **Summary comparison cards**: Field count, quality score, and issues with delta values
- **Completeness change summary**: Counts of fields improved, decreased, or unchanged
- **Fields lost in processing**: Collapsible list of fields only in source
- **New fields in processed**: Collapsible list of fields only in processed
- **Fields with changes**: Table showing before/after values for completeness, uniqueness, issues

---

## Planned Features

### Phase 9: Validation Rules
**Priority: Low | Effort: High**

User-defined validation rules per field:
- Expected data type
- Required/optional
- Min/max length
- Regex pattern
- Allowed values (enumeration)
- Date range constraints
- Numeric range constraints

Validation results:
- Pass/fail counts
- Violation examples
- Severity levels (error/warning/info)

### Phase 10: Language Detection
**Priority: Low | Effort: Medium**

For text fields:
- Detect primary language
- Identify mixed-language fields
- Language distribution
- Character set analysis (Latin, Cyrillic, etc.)

### Phase 11: Cross-field Analysis
**Priority: Low | Effort: High**

Analyze relationships between fields:
- Fields that always appear together
- Potential duplicate fields (similar values)
- Hierarchical relationships
- Conditional presence patterns

### Phase 12: Encoding Issues Detection
**Priority: Low | Effort: Medium**

Detect character encoding problems:
- Mojibake detection (garbled characters)
- Invalid UTF-8 sequences
- HTML entities in text
- Escaped characters that shouldn't be

### Phase 13: Outlier Detection
**Priority: Low | Effort: Medium**

For numeric and date fields:
- Statistical outlier detection (IQR, Z-score)
- Suspicious values (future dates, negative ages)
- Anomaly highlighting in histograms

---

## Implementation Notes

### Architecture Considerations
- All statistics are computed during the Collator phase
- Statistics stored in `status.json` per field
- UI reads from status.json via API endpoints
- Consider caching for large datasets

### Performance
- Statistics are computed on unique values (not all occurrences)
- Streaming computation to handle large datasets
- Consider sampling for very high cardinality fields

### UI/UX Guidelines
- Use consistent color coding across all quality indicators
- Provide tooltips explaining each metric
- Allow sorting/filtering by quality metrics
- Progressive disclosure (summary → details)

---

## Cultural Heritage Specific Considerations

For museum/archive data, pay special attention to:
- **Date fields**: Historical dates, date ranges, approximate dates
- **Identifiers**: Collection codes, accession numbers, URIs
- **Multilingual content**: Multiple languages in same field
- **Controlled vocabularies**: SKOS terms, AAT, ULAN references
- **Rights statements**: License URIs, copyright notices
- **Provenance**: Source attribution, data lineage

---

## References

- Original analysis code: `app/analysis/` (2014)
- Type detection: `app/analysis/TypeDetector.scala` (TypeCounter, TypeAnalysis)
- Pattern analysis: `app/analysis/TypeDetector.scala` (PatternTracker, PatternAnalysis)
- Value statistics: `app/analysis/ValueStats.scala` (StatsTracker, ValueStatistics)
- Quality statistics: `app/analysis/TreeNode.scala` (QualityStats, RecordTracker)
- Collator integration: `app/analysis/Analyzer.scala` (Collator class)
