# Development and Deployment

![Package](images/development-deployment.jpg)

Narthex is built on the basis of the [Scala Build Tool](http://www.scala-sbt.org/), so for development, a project in an Integrated Development Environment (IDE) like [IntelliJ]() can be built using a single command like [gen-idea](https://github.com/mpeltonen/sbt-idea). The [build file](https://github.com/delving/narthex/blob/master/build.sbt) defines the project.

With the **sbt** installed, and the below configuration completed, one can run Narthex in **development mode** using the **run** command and then opening a browser to [http://localhost:9000/](http://localhost:9000/), which the default location of the Play server.

## Getting started

 - Download and install [Fuseki 2.4.x](https://jena.apache.org/download/index.cgi)
 - Startup fuseki from this project's root-dir: `fuseki-server --config=./fuseki.ttl`
 - Start the Play app: `sbt run`

You're good to go!

## Building a distribution

A pre-built distribution can be downloaded from the Delving [Artifactory](http://artifactory.delving.org/artifactory/delving/narthex/) repository, or one can be built locally.

With the SBT in place and running, the **["dist"](https://www.playframework.com/documentation/2.3.x/ProductionDist)** command, which builds the project and produces a distribution zip file.

	Your package is ready in [your directory]/narthex/target/universal/narthex-?.?.?-SNAPSHOT.zip

Unpacking this file will reveal that it has a **"bin"** directory, and inside there is a script file called "narthex" for starting on Unix-like systems and a "narthex.bat" for starting on Windows.

For production deployment, the program must be started up with some extra [configuration parameters](https://www.playframework.com/documentation/2.3.x/ProductionConfiguration), especially using the **-Dconfig.file** option to tell it where to find its configuration.


## The NarthexFiles directory

Narthex creates a directory in the home of the user where it is started called **NarthexFiles**, and then a directory inside there with the name of the **"org"** from the configuration.  This way multiple instances of Narthex can be run under the same user, if they are given different port numbers, since each creates its own **org* subdirectory.

	~/NarthexFiles/demo_organization

This strategy makes it possible to run multiple instances of Narthex (for different organizations) on the same machine under the same user.

	~/NarthexFiles/demo_organization
	~/NarthexFiles/another_organization

All of the files and directories which Narthex uses are to be found within the directory corresponding to the organization id.

* ***~/NarthexFiles/org** - the organization root
	* **/factory** - defines the possible 
	* **/datasets** - all the many files associated with uploading
		* **/source** - the XML data, whether harvested or dropped-on
		* **/tree** - the analyzed data, in a directory tree
		* **/processed** the data after processing
		* **/sips** the uploaded SIP-Zip files (from the SIP-App)
	* **/raw** - the "pockets", each containing one record
	* **/sips** - the downloadable SIP-Zip files (SIP-App)


## Structure of the Narthex Triple-Store

Narthex persists all of its information on the file system and in the triple store, and the code responsible for this is carefully gathered together so that an overview is possible.

* [GraphProperties.scala](https://github.com/delving/narthex/blob/master/app/triplestore/GraphProperties.scala) - all of the URIs that Narthex creates and uses
* [Sparql.scala](https://github.com/delving/narthex/blob/master/app/triplestore/Sparql.scala) all of the SPARQL used to interact with the triple store

## The "Nave" LoD server

The public-facing server which is the counterpart to Narthex is referred to as "Nave" (yes, another part of the church).  It gets its data from the triple store, so the triple store is the point of transfer between the two systems.  Nave must periodically query for changes and then act on them.  It must be able to interpret the stored triples, and follow links created by the terminology mapping and vocabulary mapping to build its index and to display the results properly in good LoD tradition.

There is currently a working Nave LoD server, but its release into open source is not yet complete.

---

Contact: info@delving.eu;