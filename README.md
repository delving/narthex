
# Narthex

| >>>>>>>>>>>>>>>>>>>> | What? |
|-----|------|
| ![](public/images/narthex-1.png?raw=true) | The **Narthex** is a place which separates the entrance of the church from the inner **Nave**, and it was considered to be a place of penitence. This software is the place where you can put your metadata, and by doing so be able to see it in **cross section**. All values that appear at every path are collected and counted and you have instant insight into what your data contains, and how it can be improved.

## Introduction

[Narthex](http://en.wikipedia.org/wiki/Narthex) is firstly an online XML analyzer for discovering the structure and content of cultural heritage metadata files. It can acquire data either by dropping an XML(gzip) file into the application, or by harvesting records via OAI-PMH or directly from the AdLib API (the first custom technique). The analysis results can be browsed as well as made available via an API to other software. The acquired datasets can be harvested from Narthex using OAI-PMH.

Analysis is only the first step, since features will be added to Narthex enabling the improvement of the data as well.  Currently, there is a Terminology Mapping feature which allows users to map utilized terms from the source to concepts expressed in SKOS/XML.

Technologies: **AngularJS**, **D3JS**, **RequireJS**, **Playframework**, and **Akka**. (Thanks to James Ward and the TypeSafe Activator and WebJars people!)|

## Background

Initially, Narthex was part of the CultureBrokers project, co-funded by the Swedish Arts Council and the British Museum, and developed by by Delving BV. Subsequent developments for Terminology Mapping were developed for the [RCE](http://www.cultureelerfgoed.nl/), and further developments to expand the statistical analysis are also funded by RCE.

## Development

The project is quite small, with very few dependencies, and does not require any storage technology on the back-end except for file system.  As a one-page online application it consists of a front end built with [AngularJS](https://angularjs.org/), and a back-end built in Scala with the [Play Framework](http://www.playframework.com/). The analysis work is done using the [Akka](http://akka.io/) actor framework, and effectively employs all available processor cores to do the work concurrently.  Storage of status information, records, and enrichments is done using [BaseX](http://www.basex.org/)

### Distribution

Since the project has only a few simple dependencies, it is very easy to deploy on a server for usage.  With the git repository cloned you can run "sbt" (Scala Build Tool) to create a distribution with the "dist" command, or you can fetch a pre-packaged distribution [provide nexus link].  

The distribution is in the form of a zip-file which you must unpack, and then a script or batch file can be used to start the server.

* **narthex-X.X.zip** - the distribution zip file
* **narthex-X.X/bin/narthex** - the shell script (unix/osx)
* **narthex-X.X/bin/narthex.bat** - the batch file (windows)

### Storage and API

All files related to the program are stored in a folder called **NarthexFiles** in the user's home directory.  Inside that folder there is a sub-folder for every organization instance.  Multiple instances of Narthex can therefore be run on the same server machine.

Inside the organization's folder here are folders for all **uploaded** XML zip files, and a corresponding folders for the result of the analysis called **analyzed**.  The sub-folders of **analyzed** have names corresponding exactly to the files which were uploaded, and each of these contains the following:

* **index.json** - a full tree-shaped description of the schema of the file
* **"exploded tree"** - a tree of sub-folders corresponding to the tree of the derived XML schema, each folder containing:
	* **status.json** - a description of the field's analysis, indicating what other information is available (sample and histogram files) and the count of unique values
	* **unique.txt** - a complete list of all values appearing in a field
	* **histogram.txt** - a complete histogram, sorted frequent to infrequent, with occurrence counts
	* **sample-N.json** - a number of files of different maximum sizes (N) containing random samples of the values encountered, for display
	* **histogram-N.json** - a number of files (N) containing partial or full histograms of the values, for display

Every node of the tree contained in *index.json* also contains a *path* attribute, which leads to the directory in the *"exploded tree"*.  All of these files are created during the analysis process and are then made available via an API, served up simply as as **file system assets**.

## XML Storage

There is BaseX database used to store the dataset's current state and related important facts, and another database used to store records.  

When viewing the histogram of a source field which contains unique values, you have the option of using that field as a unique record identifier, and once that information is available, the option to save the dataset will be available.

### Dataset

The document stored in BaseX for the datasets, which maintains their current states and some important facts looks like this:

	<narthex-datasets>
		<narthex-dataset name="[Dataset Name]">
			<origin>
				<type>[origin-sip-source | orgin-sip-harvest | origin-drop | origin-harvest]</type>
				<time>[when the dataset was acquired]</time>
			</origin>
			<status>
				<state>[State]</state>
				<time>[Time the state was changed]</time>
				<percent>[Percent Complete]</percent>
				<workers>[Worker Count]</workers>
				<error>[Error message if there is one]</error>
			</status>
			<delimit>
				<recordRoot>[Record Root]</recordRoot>
				<uniqueId>[Unique Identifier]</uniqueId>
				<recordCount>[Record Count]</recordCount>
			</delimit>
			<namespaces>
				<prefix1>[URI 1]</prefix1>
				<prefix2>[URI 2]</prefix2>
			</namespace>
			<harvest>
				<harvestType>[pmh or adlib]</harvestType>
				<url>[Base URL of the server]</url>
				<dataset>[name of the server dataset]</dataset>
				<prefix>[The metadata prefix]</prefix>
			</harvest>
		</narthex-dataset>
		...
	</narthex-datasets>
	
* **State** - a string containing one of the following values
	* "state-deleted"
	* "state-empty"
	* "state-harvesting"
	* "state-ready"
	* "state-splitting"
	* "state-analyzing"
	* "state-analyzed"
	* "state-saving"
	* "state-saved"
	* "state-published"

### Records

Assuming that the data files being submitted contain records in XML, the path corresponding to the unique identifier of each record can be defined.  Narthex will verify that its values are unique and use it to discover which XML element contains each record.  With record root and unique id identified, the records can be separated and stored individually in an XML database like [BaseX](http://basex.org/) where they can be efficiently queried.  

With the records stored, it becomes possible to browse records together with the analysis results.  On the terminology page of the application, selecting a value from the histogram for a field fetches the records containing that value.  This way users can see how the value was used in context.

Narthex stores records in the XML database under names which are generated from an MD5 hash algorithm of their textual contents.  To provide for more effective exporting of the database to the file system (a handy BaseX feature), the files are stored within three levels of hierarchy according to the first three characters of the hash:

	[Dataset Name]/f/3/f/f3fd9fec17fd8e6f3278a58b9eb3591f.xml

When a new version of the same dataset arrives, this hash will be used to determine if there has been a change to an existing record or not.  The idea is that only changed records will be propagated, whether or not the contributor of the source file is able to provide incremental updates.

### Enrichment: Terminology Mapping

When Narthex has perform its analysis, the fields utilizing a limited (presumably somewhat controlled) vocabulary will be revealed.  For proper integration with other datasets, it may be necessary to translate these (perhaps local) vocabulary terms into choices from a shared terminology resource, represented as a [SKOS](http://www.w3.org/2004/02/skos/) thesaurus.

With the histogram of a terminology field in view, you can choose to map to a vocabulary.  The vocabularies must be made available in a directory **~/NarthexFiles/skos/** in the form of XML files, appropriately named, containing SKOS XML.  Examples:

	Object_Types.xml
	Styles_and_Periods.xml

These names, with underscores removed, will be presented to the user so that they can decide which vocabulary to query.

Vocabularies are queried by traversing the concepts in memory and performing string comparisons on the appropriate preferred and alternate labels.  The string comparison is an algorithm from the [Stringmetic](https://rockymadden.com/stringmetric/) library called **RatcliffObershelpMetric** but any other sysem could be used.

The terminology mappings are from URI to URI, and they are stored in BaseX:

	<mappings>
		<mapping>
			<source>[Source URI]</source>
			<target>[Target URI]</target>
			<prefLabel>[Preferred Label]</prefLabel>
			<vocabulary>[Vocabulary Name]</vocabulary>
		</mapping>
	</mappings>

Enriched records can be fetched from Narthex via its API or via OAI-PMH harvesting.

---

## Future

The functionality of Narthex suggests and leads the way to some interesting potential future developments.  This program represents the first phase of a workflow which can facilitate online publishing of metadata, but in the future it could be used for various cleaning, enriching, and transforming tasks.  It could become a kind of metadata repository where data owners keep the published version of their data available.

### Link Checking

In cultural heritage and elsewhere, data contains links to digital objects. Often these links point to a different computers online, and people only find out about those servers failing after they have searched the data.  Results show failures to fetch the image, which is disappointing, and a persistent problem.  These broken links can be found much earlier and make the search experience more consistent.

A field identified to contain a URL to a digital object could have an "agent" attached to it.  This agent would wake up periodically, perhaps daily, and spot-check one of the values in the list of URLs.  If it succeeds, the agent is finished for now.  If the digital object is no longer available for fetching, the agent would escalate to a higher alarm level and start a bigger spot-check of random URLs.  If there are more failures, this agent could contact the data's owner and inform them of the problem.

There may be hundreds of different datasets in Narthex and each one could have some of these agents attached to them.  All data owners could be assured that their links are intact.  It would also be possible to have Narthex agents slowly traverse a list of URLs (avoiding server crashes) and fetch all of the digital objects into a local cache.

### Incremental Update Buffering

The capability of a data provider to deliver only changed records from their data is not always present, in which case the best available way to access their data is in the form of a complete dump of everything.  At the same time, there are a number of motivations for only revealing changed records rather than all records, the most straightforward being that there is less work to do.  For example, in an index only the deleted records need be removed and the new ones added, which is much faster than deleting an entire (some are large!) collection from an index and then reindexing the whole thing.

This problem could be solved for the time being with Narthex.  Using the hash-based record storage, it becomes possible to periodically upload entire dumps of a dataset, and have Narthex detect which records have changed and which have not, so that any other machine fetching data from Narthex could receive only the changed records rather than all of them.  With "procession",  Narthex plays the role of buffer ensuring that other systems get the incremental changes they need and compensating for data providers not yet able to deliver incremental changes from their collection registration systems.

### Syntax Normalization

With all of the values ever appearing in a field visible in one panel, it would be possible to have another panel which contains a snippet of syntax normalization code which maniuplates those values.  Editing the code snippet could be fully interactive, where a pause in typing causes the code to be interpreted and run on all of the actual values, producing a list of corrected values.

Accompanying the normalization code snippet could be another one for validation.  With a judgement in place of which values are considered valid and which are not, it would be possible to filter the values arising from the normalization code, and only show the ones not yet valid.

The combination of normalization code and validation code with the list of all values for a field is exactly what is needed for efficient syntax normalization. The code would then be executed on those values on-the-fly whenever a server fetches records.  The language of choice for this would probably be [Python](https://www.python.org/).

### Transformation to RDF

Combined with another element of the CultureBrokers project called the [X3ML Engine](https://github.com/delving/x3ml), a mapping to RDF can be built, and the source data could be presented as meaningful RDF, according to an ontology such as the [CIDOC-CRM](http://www.cidoc-crm.org/), and a triple store could be filled and exposed for querying.  The X3ML Engine works on the basis of X3ML files (XML syntax) which currently have to be built by hand.

The Narthex user interface or something like it will also probably be the best starting point for building an interactive multi-user tool for building X3ML files.  The choices of Domain, Path, and Range should take place in something like the tree that Narthex displays.  While building an X3ML mapping from XML to RDF, it would be possible to have a running visualization of all of the triples that have changed (disappeared:red or appeared:green) as a result of a change made to the mapping.

---

Contact: Gerald de Jong &lt;gerald@delving.eu&gt;