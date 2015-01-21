# Skosification

Administrators can choose to "skosify" a field in a source dataset once it has been processed and pushed into the triple store.  The reason for doing this will be to initiate the process of creating and maintaining mappings from terms used in the chosen field to SKOS concepts selected from existing shared vocabularies or concept schemes.

Skosification involves creating a new associated dataset which will contain a minimal SKOS vocabulary with an entry for each unique value of the skosified field.  The challenge in this process is to maintain these SKOS concepts and to be able to accommodate new entries which may appear during incremental updates.

## The Skosifier

The Skosifier is an actor which periodically scans for work to do and then goes about its business at a rate which does not overwhelm the underlying system.  It's job is to create and maintain all of the skosified dataset source fields.

### Work Detection

The first step for the Skosifier is to detect where work is to be done.  For this it checks for any datasets which have been tagged as containing skosified fields.  It checks the values of these fields in the dataset to see if there are values for a given field which are still RDF literals.  These are triples which need adjustment because a skosified field needs to contain URIs.

	@PREFIX nx: <http://github.com/delving/narthex/wiki/Dataset-Info-Attributes#> .	SELECT ?dataset ?fieldProperty ?fieldValue
	WHERE {
		GRAPH ?g {
			?dataset nx:skosField ?fieldProperty
			?record nx:belongsTo ?dataset
			?record ?fieldProperty ?fieldValue
			FILTER isLiteral(?fieldValue)
		}
	}
	LIMIT 12

The result of this query is up to 12 pieces of work to do.  A piece of work consists of a change to a dataset involving correcting a given value.

* **dataset**: the dataset to which the record belongs
* **fieldProperty**: the URI of the property that has been skosified
* **fieldValue**: a literal value which occurs for the given property (needs fixing)

### Check and Correct

Each literal value will be transformed into its associated URI which is derived from the literal by embedding it in a predictable pattern.  If this URI does not refer to a corresponding entry in the associated SKOS dataset, a new entry will be created.  Either way, the literal is then replaced with the new URI.

This SPARQL uses substitutions for the above for $dataset, $fieldProperty, $fieldValue and adds a new value $fieldValueURI for addition.  Also a URI for the graph storing the dataset's SKOS concepts $datasetSkos is included.

#### Check existence:

The return value of this is the URI of rdf:concept if it exists, otherwise the result is empty.

	@PREFIX nx: <http://github.com/delving/narthex/wiki/Namespace#> .
	@PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
	@PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
	WITH GRAPH <$datasetSkos>
	SELECT ?type
	WHERE {
		<$fieldValueUri> rdf:type ?type .
		<$fieldValueUri> nx:belongsTo <$dataset> .
	}

#### Add to SKOS if not

	@PREFIX nx: <http://github.com/delving/narthex/wiki/Namespace#> .
	@PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
	@PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
	WITH GRAPH 
	INSERT DATA {
	    GRAPH <$datasetSkos> {
			<$fieldValueUri> skos:type skos:Concept .
			<$fieldValueUri> skos:prefLabel "$fieldValue" .
			<$fieldValueUri> nx:belongsTo <$dataset> .
		}
	}

#### Make the fix:

	@PREFIX nx: <http://github.com/delving/narthex/wiki/Namespace#> .
	WITH GRAPH ?g
	DELETE { 
		?record <$fieldProperty> "$value" .
	}
	INSERT {
		?record <$fieldProperty> <$fieldValueURI> .
	}
	WHERE {
		?record <$fieldProperty> "$value" .
		?record nx:belongsTo <$dataset> .
	}
	

### Wash, Rinse, Repeat

The Skosifier will wake up from time to time to check if there is work to do, and when it discovers work, it will proceed as quickly as is practically possible, ensuring that it never undermines the performance of the triple store for other concurrent clients.  It will do its work in chunks, querying for N pieces of work to do, and after it finishes, it will pause for a moment. How intensely it works will be configurable.



