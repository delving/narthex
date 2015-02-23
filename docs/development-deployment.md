# Development and Deployment

Narthex is built on the basis of the [Scala Build Tool](http://www.scala-sbt.org/), so for development, a project in an Integrated Development Environment (IDE) like [IntelliJ]() can be built using a single command like [gen-idea](https://github.com/mpeltonen/sbt-idea).

## Building a distribution

A pre-built distribution can be downloaded from the Delving [Artifactory](http://artifactory.delving.org/artifactory/delving/narthex/) repository, or one can be built locally.

With the SBT in place and running, the **"dist"** command, which builds the project and produces a distribution zip file.

	Your package is ready in [your directory]/narthex/target/universal/narthex-?.?.?-SNAPSHOT.zip

Unpacking this file will reveal that it has a **"bin"** directory, and inside there is a script file called "narthex" for starting on Unix-like systems and a "narthex.bat" for starting on Windows.  But before starting up makes sense, the triple store has to be running and the configuration has to be done.

## Configuring the Fuseki triple store

Documentation about how to configure Fuseki should be found at the [source](http://jena.apache.org/documentation/serving_data/#getting-started-with-fuseki) rather than here, but the main thing to set up a TDB triple store instance with an appropriate name.  The URL to this triple store is part of the configuration of Narthex.

## Narthex configuration

A minimal configuration of Narthex would be like this:

	orgId = "org"
	
	triple-store = "http://localhost:3030/lodd2"
	
	domains = {
	  narthex = "http://localhost:9000"
	  nave = "http://acc.lodd2.delving.org"
	}

## The NarthexFiles directory

Narthex creates a directory in the home of the user where it is started called **NarthexFiles**, and then a directory inside there with the name of the **"org"** from the configuration.  This way multiple instances of Narthex can be run under the same user, if they are given different port numbers, since each creates its own **org* subdirectory.

