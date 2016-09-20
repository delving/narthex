# Narthex
[![Build Status](https://travis-ci.org/delving/narthex.svg)](https://travis-ci.org/delving/narthex)
[![codecov.io](https://codecov.io/github/delving/narthex/coverage.svg)](https://codecov.io/github/delving/narthex)

## Audience
This page is targeted at developers. If you like to know about the concepts behind Narthex, start at the [index of the docs](docs/README.md).

## Getting started

Narthex is based on Playframework, which uses [sbt](http://www.scala-sbt.org) as its build tool, so install it: `brew install sbt`

Convince yourself you can run tests and that sbt works: `sbt test`.

Narthex uses 'fuseki' for persistence, here's how to get it up and running:

 - Download and install [Fuseki 2.4.x](https://jena.apache.org/download/index.cgi)
 - Startup fuseki from this project's root-dir:

```bash
cd [fuseki_homedir]

./fuseki-server --config=[/full/path_to_nathex_project_dir]/fuseki.ttl
```

Don't use the '~' character for your homedir, Fuseki runs inside the JVM and won't replace that with the path to your homedir.

You will notice that it creates a `./fuseki_data` directory which is in .gitignore. To start with a fresh database, stop fuseki and delete the data-directory.

 - Narthex uses your local filesystem for persistence, and we must initialize it. Perform the following steps from the project root-dir.
    
```bash
mkdir -p ~/NarthexFiles/devorg/factory/edm
cp docs/files/edm* ~/NarthexFiles/devorg/factory/edm
```
    
 - Start the Play app: `sbt run` and go to the [app-homepage](http://localhost:9000)
 - Login with `admin`/`admin`, click on 'Datasets' => 'New dataset', enter 'first' and drag [docs/files/myfirst.sip.zip](docs/files/myfirst.sip.zip) to see Narthex in action.
 
That's all.

## Building a distribution

A pre-built distribution can be downloaded from the Delving [Artifactory](http://artifactory.delving.org/artifactory/delving/eu/delvin/narthex/) repository, or one can be built locally.

With the SBT in place and running, the **["dist"](https://www.playframework.com/documentation/2.3.x/ProductionDist)** command, which builds the project and produces a distribution zip file.

	Your package is ready in [your directory]/narthex/target/universal/narthex-?.?.?-SNAPSHOT.zip

Unpacking this file will reveal that it has a **"bin"** directory, and inside there is a script file called "narthex" for starting on Unix-like systems and a "narthex.bat" for starting on Windows.

For production deployment, the program must be started up with some extra [configuration parameters](https://www.playframework.com/documentation/2.3.x/ProductionConfiguration), especially using the **-Dconfig.file** option to tell it where to find its configuration.


## The NarthexFiles directory

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

## Running using docker

To do this, we need to take care of a couple of things:

1. A mount of the app-config file containing overrides of the defaults in [application.conf](../conf/application.conf) which resides on the host
2. A docker 'named data container' for the Narthex data directory
3. Access to the tripleStore running on the host-machine (this part isn't dockerized). Execute `$ docker-machine inspect | grep HostOnlyCIDR`. 
In our case the address was `192.168.99.1` and we need it for the contents of the override-conf file below

The contents of the override-file must look like this.
*Note* the include statement is required or defaults won't load:

```
include "application.conf"
narthexHome = "/opt/docker/narthexdata"
triple-store = "http://192.168.99.1:3030/devorg"
```

Create your 'dev' shared volume on top of the empty one (which is [maintained elsewhere](https://github.com/delving/narthex-datadir-docker)):
`docker create -v /narthexdata --name narthex-data delvingplatform/narthex-data-initial:1 /bin/true` 
 
And run  the app :
```bash
docker run --rm --net host --volumes-from narthex-data --name narthex \
-v /Users/hw/Desktop/narthex_conf:/opt/docker/conf \
delvingplatform/narthex:[your_version]
```

After that, Narthex is running (inside the docker-container) at http://localhost:9000
**Note:** If you are on Mac or Windows, Narthex runs inside your docker-machine. In our case, `$ docker-machine ls` outputs:

```
NAME      ACTIVE   DRIVER       STATE     URL                         SWARM   DOCKER    ERRORS
default   *        virtualbox   Running   tcp://192.168.99.100:2376           v1.12.0   
```

So, I can reach narthex at http://192.168.99.100:9000

## Docker development
This assumes you have installed docker.

To create a new local image: `sbt docker:publishLocal`

Run `docker images` and see that 'narthex' was added to your local images.

## The "Nave" LoD server

The public-facing server which is the counterpart to Narthex is referred to as "Nave" (yes, another part of the church).  It gets its data from the triple store, so the triple store is the point of transfer between the two systems.  Nave must periodically query for changes and then act on them.  It must be able to interpret the stored triples, and follow links created by the terminology mapping and vocabulary mapping to build its index and to display the results properly in good LoD tradition.

---

Contact: info@delving.eu;
