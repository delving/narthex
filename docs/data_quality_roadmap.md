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

---

## Planned Features

### Phase 4: Quality Indicators in Tree View
**Priority: High | Effort: Low**

Add visual indicators to the schema tree to highlight quality issues at a glance:
- Warning badge for low completeness (< 50%)
- Alert icon for whitespace issues
- Mixed type indicator
- Color-coded completeness (green/yellow/red)

```
Example tree node:
[folder] metadata
  [field] title (98%) ✓
  [field] description (45%) ⚠
  [field] date (100%) ✓ [DATE]
  [field] creator (72%) ⚠ [MIXED]
```

### Phase 5: Quality Summary Dashboard
**Priority: High | Effort: Medium**

Overview page showing aggregated quality metrics for entire dataset:
- Total fields analyzed
- Fields with quality issues (grouped by issue type)
- Overall dataset quality score
- Top 10 problematic fields
- Completeness distribution chart

### Phase 6: Data Quality Score
**Priority: Medium | Effort: Medium**

Calculate composite quality score per field combining:
- Completeness weight: 40%
- Type consistency weight: 25%
- No whitespace issues: 15%
- Reasonable value lengths: 10%
- No empty values: 10%

Score ranges:
- 90-100: Excellent
- 70-89: Good
- 50-69: Fair
- 0-49: Poor

### Phase 7: Export Quality Report
**Priority: Medium | Effort: Low**

Generate exportable reports in multiple formats:
- **CSV**: All field statistics in tabular format
- **JSON**: Full structured data for programmatic use
- **PDF**: Formatted report for stakeholders (future)

Report contents:
- Dataset metadata
- Per-field statistics
- Quality scores
- Issue summary
- Recommendations

### Phase 8: Source vs Processed Comparison
**Priority: Medium | Effort: Medium**

Side-by-side comparison view:
- Compare field counts between source and processed
- Identify fields lost in processing
- Compare completeness changes
- Track type changes through processing pipeline

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
