# Terminology Mapping

[to be done]

## Skosification

Administrators can choose to "skosify" a field in a source dataset once it has been processed and pushed into the triple store.  The reason for doing this will be to initiate the process of creating and maintaining mappings from terms used in the chosen field to SKOS concepts selected from existing shared vocabularies or concept schemes.

Skosification involves creating a new associated dataset which will contain a minimal SKOS vocabulary with an entry for each unique value of the skosified field.  The challenge in this process is to maintain these SKOS concepts and to be able to accommodate new entries which may appear during incremental updates.

