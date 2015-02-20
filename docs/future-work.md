## Future

The functionality of Narthex suggests and leads the way to some interesting potential future developments.  This program represents the first phase of a workflow which can facilitate online publishing of metadata, but in the future it could be used for more mapping, cleaning, enriching, and transforming tasks.  It is to become a kind of metadata repository where data owners keep the published version of their data available.

### Link Checking

In cultural heritage and elsewhere, data contains links to digital objects. Often these links point to a different computers online, and people only find out about those servers failing after they have searched the data.  Results show failures to fetch the image, which is disappointing, and a persistent problem.  These broken links can be found much earlier and make the search experience more consistent.

A field identified to contain a URL to a digital object could have an "agent" attached to it.  This agent would wake up periodically, perhaps daily, and spot-check one of the values in the list of URLs.  If it succeeds, the agent is finished for now.  If the digital object is no longer available for fetching, the agent would escalate to a higher alarm level and start a bigger spot-check of random URLs.  If there are more failures, this agent could contact the data's owner and inform them of the problem.

There may be hundreds of different datasets in Narthex and each one could have some of these agents attached to them.  All data owners could be assured that their links are intact.  It would also be possible to have Narthex agents slowly traverse a list of URLs (avoiding server crashes) and fetch all of the digital objects into a local cache.

### Integrated Syntax Normalization

Currently syntax normalization is performed with the SIP-Creator together with the process of mapping to a given target schema, since this tool already exists and has proven itself.  The SIP-Creator is a Java application, and therefore has to be run locally, so eventually we would like to replace its function with processes which can be done in the online browser interface of Narthex.  We intend to build on our experience with the SIP-Creator to create a more powerful approach in the future.

With all of the values ever appearing in a field visible in one panel, it would be possible to have another panel which contains a snippet of syntax normalization code which takes those values and shows the normalized values next to them.  Editing the code snippet could be fully interactive, where a pause in typing causes the code to be interpreted and run on all of the actual values, producing a list of corrected values.

Accompanying the normalization code snippet could be another one for validation.  With a judgement in place of which values are considered valid and which are not, it would be possible to filter the values arising from the normalization code, and only show the ones not yet valid.

The combination of normalization code and validation code with the list of all values for a field is exactly what is needed for efficient syntax normalization. The code would then be executed on those values on-the-fly whenever a server fetches records.  The language of choice for this would probably be [Python](https://www.python.org/).

### Transformation to RDF using X3ML Engine

Combined with another element of the CultureBrokers project called the [X3ML Engine](https://github.com/delving/x3ml), a mapping to RDF can be built, and the source data could be presented as meaningful RDF, according to an ontology such as the [CIDOC-CRM](http://www.cidoc-crm.org/), and a triple store could be filled and exposed for querying.  The X3ML Engine works on the basis of X3ML files (XML syntax) which currently have to be built by hand.

The Narthex user interface or something like it will also probably be the best starting point for building an interactive multi-user tool for building X3ML files.  The choices of Domain, Path, and Range should take place in something like the tree that Narthex displays.  While building an X3ML mapping from XML to RDF, it would be possible to have a running visualization of all of the triples that have changed (disappeared:red or appeared:green) as a result of a change made to the mapping.

