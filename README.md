
# Narthex

| > | What? |
|-----|------|
| ![](public/images/narthex-1.png?raw=true =300x480) | The **Narthex** is a place which separates the entrance of the church from the inner **Nave**, and it was considered to be a place of penitence. This software is the place where you can put your metadata, and by doing so be able to see it in cross section. All values that appear at every path are collected and counted and you have instant insight into what your data contains, and how it can be improved.|

## Introduction

[Narthex](http://en.wikipedia.org/wiki/Narthex) is an online XML analyzer for preparing cultural heritage metadata files for consumption by other software.  You drop your XML file (zipped) onto Narthex and it will do complete comprehensive rigorous analysis, revealing the schema as well as everything about the content of all non-empty elements and attributes.  The results can be browsed as well as made available via an API to other software.

This is only the first step. The next phases in the project will involve building vocabulary alignments, performing link checks, geo-coordinate conversions or whatever else is necessary to prepare the data for entry into the [nave](http://en.wikipedia.org/wiki/Nave) of the CultureCloud.

## Background

Narthex makes up part of the CultureBrokers project, co-funded by the Swedish Arts Council and the British Museum, and developed by by Delving BV.

## Development

The project is quite small, with very few dependencies, and does not require any storage technology on the back-end except for file system.  As a one-page online application it consists of a front end built with [AngularJS](https://angularjs.org/), and a back-end built in Scala with the [Play Framework](http://www.playframework.com/). The analysis work is done using the [Akka](http://akka.io/) actor framework, and effectively employs all available processor cores to do the work concurrently.

### Distribution

Since the project has only a few simple dependencies, it is very easy to deploy on a server for usage.  With the git repository cloned you can run "sbt" (Scala Build Tool) to create a distribution with the "dist" command, or you can fetch a pre-packaged distribution [provide nexus link].  

The distribution is in the form of a zip-file which you must unpack, and then a script or batch file can be used to start the server.

* **narthex-X.X.zip** - the distribution zip file
* **narthex-X.X/bin/narthex** - the shell script (unix/osx)
* **narthex-X.X/bin/narthex.bat** - the batch file (windows)

### Storage and API

All files related to the program are stored in a folder called **NARTHEX** in the user's home directory.  Inside that folder there is a sub-folder for every user which is named according to their email address, and it contains **user.json** for storing the hash of the password that the user has set up.

Inside the user's folder here are folders for all **uploaded** XML zip files, and a corresponding folders for the result of the analysis called **analyzed**.  The sub-folders of **analyzed** have names corresponding exactly to the files which were uploaded, and each of these contains the following:

* **index.json** - a full tree-shaped description of the schema of the file
* **status.json** - an indication of the state of the analysis (while in progress)
* **"exploded tree"** - a tree of sub-folders corresponding to the tree of the derived XML schema, each folder containing:
	* **status.json** - a description of the field's analysis, indicating what other information is available (sample and histogram files) and the count of unique values
	* **unique.txt** - a complete list of all values appearing in a field
	* **histogram.txt** - a complete histogram, sorted frequent to infrequent, with occurrence counts
	* **sample-N.json** - a number of files of different maximum sizes (N) containing random samples of the values encountered, for display
	* **histogram-N.json** - a number of files (N) containing partial or full histograms of the values, for display

Every node of the tree contained in *index.json* also contains a *path* attribute, which leads to the directory in the *"exploded tree"*.  All of these files are created during the analysis process and are then made available via an API, served up simply as as *assets*.

## Future

The functionality of NARTHEX suggests and leads the way to some interesting potential future developments.  This program represents the first phase of a work-flow which can provide for online publishing of metadata in various forms.

### Field Enrichment

When Narthex has perform its analysis, any vocabularies used in practice for terminology fields in the source data will be revealed.  When a full list of utilized terminology values is available, it is not a great deal of work to build an alignment interface in which each found value is associated with the URI of an entry from a shared vocabulary.

The source data can then be rendered with the vocabulary URI assignments inserted, which is an important part of integrating data together from various sources into storage where they can be collectively accessed.

Various other possibilities exist, such as link checking of URL fields, and specific improvement strategies for date fields etc.

### Records for Indexing/Search/Harvest

Assuming the source data consists of records, they can be marked and used.  With the schema in view, it is possible to derive or intentionally assign both a record root element and another element or attributre containing the record's unique identifier, and that allows the source to be split up into identified records.  These records could be inserted into an XML database like [BaseX](http://basex.org/) or [Elastic Search](http://www.elasticsearch.org/) for searching, and made available for harvest via OAI-PMH or some other API interface.

### Transformation to RDF

Combined with another element of the CultureBrokers project called the [X3ML Engine](https://github.com/delving/x3ml), a mapping to RDF can be built, and the source data could be presented as meaningful RDF, according to an ontology such as the [CIDOC-CRM](http://www.cidoc-crm.org/), and a triple store could be filled and exposed for querying.

---

Contact: Gerald de Jong &lt;gerald@delving.eu&gt;