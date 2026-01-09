# Dataset Statistics Implementation Guide

This document describes the dataset statistics functionality for reimplementation in Play/Scala.

## Overview

The Dataset Statistics page provides an overview of all datasets in the system, comparing the record counts between:
- **Narthex** (metadata stored in SPARQL/RDF triplestore)
- **Elasticsearch** (search index)

This helps identify datasets with indexing issues such as missing records or datasets that haven't been indexed at all.

## URL Endpoint

```
GET /statistics/datasets/
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    Dataset Statistics Page                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────┐         ┌──────────────────┐              │
│  │   SPARQL Store   │         │  Elasticsearch   │              │
│  │   (Fuseki)       │         │                  │              │
│  │                  │         │                  │              │
│  │  Dataset specs   │         │  Indexed docs    │              │
│  │  Record counts   │         │  per spec        │              │
│  │  Valid/Invalid   │         │                  │              │
│  │  Deleted flag    │         │                  │              │
│  └────────┬─────────┘         └────────┬─────────┘              │
│           │                            │                         │
│           └──────────┬─────────────────┘                         │
│                      ▼                                           │
│           ┌──────────────────┐                                   │
│           │  Merge & Compare │                                   │
│           │                  │                                   │
│           │  Categorize:     │                                   │
│           │  - Correct       │                                   │
│           │  - Not Indexed   │                                   │
│           │  - Wrong Count   │                                   │
│           │  - Deleted       │                                   │
│           └──────────────────┘                                   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## Data Sources

### 1. SPARQL Query (Narthex Metadata)

Query the triplestore for dataset metadata stored by Narthex:

```sparql
SELECT * WHERE {
  graph ?g {
    ?s <http://schemas.delving.eu/narthex/terms/datasetSpec> ?spec ;
       <http://schemas.delving.eu/narthex/terms/datasetRecordCount> ?recordCount ;
       <http://schemas.delving.eu/narthex/terms/processedValid> ?processedValid;
       <http://schemas.delving.eu/narthex/terms/processedInvalid> ?processedInvalid.
    OPTIONAL { ?s <http://schemas.delving.eu/narthex/terms/deleted> ?deleted . }
  }
}
LIMIT 500
```

**Narthex Namespace**: `http://schemas.delving.eu/narthex/terms/`

**Fields returned**:
| Field | Predicate | Description |
|-------|-----------|-------------|
| `spec` | `narthex:datasetSpec` | Dataset specification name (unique identifier) |
| `recordCount` | `narthex:datasetRecordCount` | Total number of records in dataset |
| `processedValid` | `narthex:processedValid` | Number of valid (successfully processed) records |
| `processedInvalid` | `narthex:processedInvalid` | Number of invalid records |
| `deleted` | `narthex:deleted` | Optional boolean "true"/"false" - if dataset is deleted |

### 2. Elasticsearch Aggregation

Query Elasticsearch for document counts per dataset spec:

```json
{
  "size": 0,
  "aggs": {
    "delving_spec": {
      "terms": {
        "field": "delving_spec.raw",
        "size": 500
      }
    }
  }
}
```

**Response structure**:
```json
{
  "hits": {
    "total": {
      "value": 1234567
    }
  },
  "aggregations": {
    "delving_spec": {
      "buckets": [
        { "key": "dataset-name-1", "doc_count": 5000 },
        { "key": "dataset-name-2", "doc_count": 3000 }
      ]
    }
  }
}
```

## Data Model

### NarthexDataSet

```scala
case class NarthexDataSet(
  spec: String,           // Dataset specification name
  recordCount: Int,       // Total records in Narthex
  invalid: Int,           // Invalid/failed records
  valid: Int,             // Valid/processed records
  esCount: Int,           // Documents in Elasticsearch
  deleted: Boolean        // Whether dataset is marked deleted
)
```

## Processing Logic

### Step 1: Fetch Narthex Datasets

```scala
def getNarthexDatasets(sparqlEndpoint: String): Map[String, NarthexDataSet] = {
  // Execute SPARQL query
  val results = executeSparqlQuery(sparqlEndpoint, NARTHEX_QUERY)

  // Parse results into map keyed by spec name
  results.bindings.map { binding =>
    val spec = binding("spec").value
    val dataset = NarthexDataSet(
      spec = spec,
      recordCount = binding("recordCount").value.toInt,
      invalid = binding("processedInvalid").value.toInt,
      valid = binding("processedValid").value.toInt,
      esCount = 0,  // Will be filled in later
      deleted = binding.get("deleted").map(_.value == "true").getOrElse(false)
    )
    spec -> dataset
  }.toMap
}
```

### Step 2: Fetch Elasticsearch Counts

```scala
def getIndexedDatasets(esClient: ElasticClient, indexName: String): (Long, Map[String, Long]) = {
  val response = esClient.execute {
    search(indexName)
      .size(0)
      .aggs(
        termsAgg("delving_spec", "delving_spec.raw").size(500)
      )
  }.await

  val totalRecords = response.result.hits.total.value
  val specCounts = response.result.aggregations
    .terms("delving_spec")
    .buckets
    .map(b => b.key -> b.docCount)
    .toMap

  (totalRecords, specCounts)
}
```

### Step 3: Merge and Categorize

```scala
def getSpecList(sparqlEndpoint: String, esClient: ElasticClient, indexName: String): DatasetStats = {
  // Get Narthex data
  val narthexDatasets = getNarthexDatasets(sparqlEndpoint)

  // Get Elasticsearch counts
  val (totalRecords, esCounts) = getIndexedDatasets(esClient, indexName)

  // Merge: update esCount for each dataset
  val mergedDatasets = narthexDatasets.map { case (spec, dataset) =>
    val esCount = esCounts.getOrElse(spec, 0L).toInt
    spec -> dataset.copy(esCount = esCount)
  }

  // Log specs in ES but not in Narthex (orphaned)
  esCounts.keys.filterNot(narthexDatasets.contains).foreach { spec =>
    logger.warn(s"Spec $spec in Elasticsearch but missing in Narthex")
  }

  // Categorize
  val activeDatasets = mergedDatasets.filterNot(_._2.deleted)
  val deletedDatasets = mergedDatasets.filter(_._2.deleted)

  DatasetStats(
    totalRecords = totalRecords,
    totalSpecs = activeDatasets.size,
    correctDatasets = activeDatasets.filter { case (_, ds) =>
      ds.esCount == ds.valid
    }.values.toList,
    notIndexed = activeDatasets.filter { case (_, ds) =>
      ds.esCount == 0 && ds.valid > 0
    }.values.toList,
    wrongIndexCount = activeDatasets.filter { case (_, ds) =>
      ds.esCount != ds.valid && ds.esCount > 0
    }.values.toList,
    deleted = deletedDatasets.values.toList
  )
}
```

## Categorization Rules

| Category | Condition | Description |
|----------|-----------|-------------|
| **Correct** | `esCount == valid` | ES index matches valid records |
| **Not Indexed** | `esCount == 0 && valid > 0` | Has valid records but nothing in ES |
| **Wrong Count** | `esCount != valid && esCount > 0` | ES has records but count doesn't match |
| **Deleted** | `deleted == true` | Dataset marked as deleted in Narthex |

## Response Model

```scala
case class DatasetStats(
  totalRecords: Long,
  totalSpecs: Int,
  correctDatasets: List[NarthexDataSet],
  notIndexed: List[NarthexDataSet],
  wrongIndexCount: List[NarthexDataSet],
  deleted: List[NarthexDataSet]
)
```

## UI Display

### Summary Section
- Total number of datasets
- Total indexed records in Elasticsearch

### Tabbed Tables

Each tab shows a table with columns:
| Column | Description |
|--------|-------------|
| Spec | Dataset specification name |
| Total Records | `recordCount` from Narthex |
| Invalid | `processedInvalid` from Narthex |
| Valid | `processedValid` from Narthex |
| Indexed | `esCount` from Elasticsearch |

**Tabs:**
1. **Correct Datasets** - Everything is in sync
2. **Not Indexed** - Needs indexing action
3. **Wrong Index Count** - May need re-indexing
4. **Deleted Datasets** - Can be cleaned up

## Configuration

### Required Settings

```scala
// Elasticsearch
es.urls = ["http://localhost:9200"]
es.indexName = "nave"

// SPARQL Endpoint
sparql.endpoint = "http://localhost:3030/nave/sparql"
// Or use proxy endpoint that routes to Fuseki
```

### Elasticsearch Index Requirements

The index must have a `delving_spec.raw` field (keyword type) for aggregation:

```json
{
  "mappings": {
    "properties": {
      "delving_spec": {
        "type": "text",
        "fields": {
          "raw": {
            "type": "keyword"
          }
        }
      }
    }
  }
}
```

## Error Handling

1. **SPARQL Connection Failed**: Show error message, return empty Narthex data
2. **Elasticsearch Connection Failed**: Show error message, all datasets show 0 ES count
3. **Orphaned ES Data**: Log warning for specs in ES but not in Narthex (possible cleanup needed)

## Performance Considerations

1. **Caching**: Consider caching the statistics for 5-10 minutes as they don't change frequently
2. **SPARQL Limit**: Current limit is 500 datasets - increase if needed
3. **ES Aggregation Size**: Set to 500 buckets - increase for more datasets
4. **Timeout**: Set appropriate timeouts for both SPARQL and ES queries

## Example Play/Scala Controller

```scala
@Singleton
class DatasetStatisticsController @Inject()(
  cc: ControllerComponents,
  sparqlClient: SparqlClient,
  esClient: ElasticClient,
  config: Configuration
) extends AbstractController(cc) {

  private val indexName = config.get[String]("es.indexName")
  private val sparqlEndpoint = config.get[String]("sparql.endpoint")

  def statistics: Action[AnyContent] = Action { implicit request =>
    val stats = DatasetStatisticsService.getStats(sparqlEndpoint, esClient, indexName)
    Ok(views.html.statistics(stats))
  }
}
```

## Related Components

- **Narthex**: Data processing pipeline that creates the dataset metadata
- **Go Indexer**: Indexes records from RDF store to Elasticsearch
- **Fuseki**: Apache Jena Fuseki SPARQL triplestore

## Troubleshooting

| Issue | Possible Cause | Solution |
|-------|---------------|----------|
| All datasets show 0 ES count | ES connection failed | Check ES connectivity |
| No datasets shown | SPARQL query failed | Check Fuseki connectivity |
| Wrong count but close | Indexing in progress | Wait for indexing to complete |
| Wrong count (ES > valid) | Duplicate records indexed | Re-index the dataset |
| Wrong count (ES < valid) | Indexing failed partway | Re-index the dataset |
