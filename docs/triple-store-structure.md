# Structure of the Narthex Triple-Store

Narthex persists all of its information on the file system and in the associated triple store.  This of course means that source record data is pushed to the triple store after mapping for other software to use, but a number of other things must be persisted as well.  This document describes the entire structure of the triple store from the point of view of Narthex.

The main purpose here is to define the naming of the graphs in the triple store, as well as the data elements that each module requires.

Since the triple store must describe things in terms of graphs with namespaces, so when necessary we will define elements of a Narthex namespace. In time these may be migrated to standards where they seem like good choices.

## Actor Hierarchy

[to be done]

## Dataset Information

[to be done]

## Records

[to be done]

## SKOS Vocabularies

[to be done]

## Terminology Mappings

Whenever a mapping is made, information about the person making the mapping must be recorded as provenance, as well as notes they may want to record about it.

Provenance
	* mappingCreator -> narthex:user
	* mappingTime - time
	* mappingNotes

## Category Mappings

[to be done]
