# Structure of the Narthex Triple-Store

Narthex persists all of its information on the file system and in the associated triple store.  This of course means that source record data is pushed to the triple store after mapping for other software to use, but a number of other things must be persisted as well.  This document describes the entire structure of the triple store from the point of view of Narthex.

The main purpose here is to define the naming of the graphs in the triple store, as well as the data elements that each module requires.

Since the triple store must describe things in terms of graphs with namespaces, so when necessary we will define elements of a Narthex namespace. In time these may be migrated to standards where they seem like good choices.

## Users

The users who login to a Narthex instance must be identified and authenticated. Even if this is eventually to become an external system, they should be represented in the triple store as "actors" because they are the people performing mappings, and their identity therefore becomes part of the provenance information.

The definition here will be minimal, but can of course later be extended to involve roles and more fine-grained permissions.

Graph name: "narthex:users"

* user
	* userName
	* userEmail
	* userPasswordHash

## Datasets

Every dataset must have some meta-information associated with it for Narthex to work, recording some facts about the dataset and clearly indicating its state to other software accessing the triple store.

Graph name: "narthex:datasets"

	* datasetName
	* datasetPrefix
	* datasetAdministrator -> user (email for now)
	* datasetProvider
	* datasetLanguage
	* datasetRights
	* datasetNotes
	* datasetRecordCount - int
    * stateRaw - time
    * stateRawAnalyzed - time
    * stateSource - time
    * stateMappable - time
    * stateProcessable - time
    * stateProcessed - time
    * stateAnalyzed - time
    * stateSaved - time
    * harvestType
    * harvestURL
    * harvestDataset
    * harvestPrefix
    * harvestPreviousTime - time
    * harvestDelay - int
    * harvestDelayUnit
    * publishOAIPMH - flag
    * publishIndex - flag
    * publishLOD - flag
	* categoriesInclude - flag
    * processedValid - int
    * processedInvalid - int

## Records

Graph name: top rdf:about value

	* belongsTo - narthex:dataset
	* modified - time
	* [all of the record's contents]

## Mapping Provenance

Whenever a mapping is made, information about the person making the mapping must be recorded as provenance, as well as notes they may want to record about it.

	* mappingCreator -> narthex:user
	* mappingTime - time
	* mappingNotes

## Terminology Mappings

Terminology mappings make connections between terms utilized at certain chosen paths in the source records (such as technique, material, etc) and members of a set of target SKOS vocabularies.  The target vocabularies should be added to the triple store so that the terminology mappngs only need to point to the concept URI, and the rest of the information such as skos:prefLabel can be found there.

Graph name: base URI of source path, prefixes every source term

	* sourceTerm
	* targetConcept
	* provenance -> narthex:mappingProvenance

## Category Mappings

Category mappings indicate that a given source term indicates that the record containing it belongs to one or more categories.  The category names are recorded as a set of short mnemonic strings, and the categorization code uses these to compile its statistical counts.

Graph name: base URI of source path, prefixes every source term

	* sourceTerm
	* categoryNames
	* provenance -> narthex:mappingProvenance
	
## Thesarus Mappings

Thesaurus mappings make connections between two thesauri.

Graph name: based on both concept scheme identifiers somehow.

	* conceptA
	* conceptB
	* provenance -> narthex:mappingProvenance




