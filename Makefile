# Makefile for Narthex
NAME:=narthex
VERSION:=$(shell sh -c 'grep "ThisBuild / version" version.sbt | cut -d\" -f2')
MAINTAINER:="Sjoerd Siebinga <sjoerd@delving.eu>"
DESCRIPTION:="Narthex Aggregation and mapping platform."
FUSEKI:=apache-fuseki
FUSEKI_VERSION:=4.7.0
FUSEKI_VERSION_RPM:=4.7.0

# Java 21 for better Metaspace management and Groovy 4.x compatibility
JAVA_HOME:=/usr/lib/jvm/java-21-openjdk
export JAVA_HOME
export PATH:=$(JAVA_HOME)/bin:$(PATH)

# var print rule
print-%  : ; @echo $* = $($*)

# Version bumping - increments the patch version (last number)
bump-version:
	@echo "Current version: $(VERSION)"
	@NEW_VERSION=$$(echo $(VERSION) | awk -F. '{print $$1"."$$2"."$$3"."$$4+1}') && \
	echo "New version: $$NEW_VERSION" && \
	sed -i 's/version := "$(VERSION)"/version := "'$$NEW_VERSION'"/' version.sbt && \
	sed -i 's/urlArgs: "v=$(VERSION)"/urlArgs: "v='$$NEW_VERSION'"/' app/assets/javascripts/main.js && \
	sed -i 's/v=[0-9]*\.[0-9]*\.[0-9]*\.[0-9]*/v='$$NEW_VERSION'/g' app/assets/javascripts/datasetList/main.js && \
	echo "Version bumped to $$NEW_VERSION in:" && \
	echo "  - version.sbt" && \
	echo "  - app/assets/javascripts/main.js" && \
	echo "  - app/assets/javascripts/datasetList/main.js"

# Set a specific version
set-version:
	@if [ -z "$(V)" ]; then echo "Usage: make set-version V=0.8.2.99"; exit 1; fi
	@echo "Setting version to: $(V)"
	@sed -i 's/version := "[^"]*"/version := "$(V)"/' version.sbt
	@sed -i 's/urlArgs: "v=[^"]*"/urlArgs: "v=$(V)"/' app/assets/javascripts/main.js
	@sed -i 's/v=[0-9]*\.[0-9]*\.[0-9]*\.[0-9]*/v=$(V)/g' app/assets/javascripts/datasetList/main.js
	@echo "Version set to $(V)"

package:
	sbt package

compile:
	sbt compile

dist:
	sbt clean dist

run:
	sbt run

fpm-rpm:
	make dist
	unzip -d target/universal target/universal/narthex-$(VERSION).zip
	make fpm-build-rpm ARCH=amd64 FILE_ARCH=x86_64
	rpm --addsign *.rpm

fpm-build-rpm:
	fpm -s dir -t rpm -n $(NAME) -v $(VERSION) \
		--package $(NAME)-$(VERSION).$(FILE_ARCH).rpm \
		--force \
		-C . \
		--rpm-compression bzip2 --rpm-os linux \
		--url https://bitbucket.org/delving/$(NAME) \
		--description $(DESCRIPTION) \
		-m $(MAINTAINER) \
		--license "Apache 2.0" \
		-a $(ARCH) \
		target/universal/narthex-$(VERSION)/=/opt/hub3/narthex/NarthexVersion/


fpm-rpm-fuseki:
	rm -rf target/apache-jena-fuseki*
	wget -P target http://archive.apache.org/dist/jena/binaries/apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz
	tar xvzf target/apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz -C target
	make fpm-build-fuseki-rpm ARCH=amd64 FILE_ARCH=x86_64
	rpm --addsign *.rpm


fpm-build-fuseki-rpm:
	fpm -s dir -t rpm -n ${FUSEKI} -v $(FUSEKI_VERSION_RPM) \
		--package $(FUSEKI)-$(FUSEKI_VERSION_RPM).$(FILE_ARCH).rpm \
		--force \
		--depends java-21-openjdk \
		--rpm-compression bzip2 --rpm-os linux \
		--url https://jena.apache.org/documentation/fuseki2/index.html \
		--description "Apache Jena Fuseki" \
		-m $(MAINTAINER) \
		--license "Apache 2.0" \
		-a $(ARCH) \
		target/apache-jena-fuseki-${FUSEKI_VERSION}/=/opt/hub3/narthex/fuseki/ 
