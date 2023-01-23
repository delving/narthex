# Makefile for Narthex
NAME:=narthex
VERSION:=$(shell sh -c 'grep "version in" version.sbt  | cut -d\" -f2')
MAINTAINER:="Sjoerd Siebinga <sjoerd@delving.eu>"
DESCRIPTION:="Narthex Aggregation and mapping platform."
FUSEKI:=apache-fuseki
FUSEKI_VERSION:=4.7.0
FUSEKI_VERSION_RPM:=4.7.0


# var print rule
print-%  : ; @echo $* = $($*)

package:
	sbt package

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
		--depends java-1.8.0-openjdk \
		--rpm-compression bzip2 --rpm-os linux \
		--url https://jena.apache.org/documentation/fuseki2/index.html \
		--description "Apache Jena Fuseki" \
		-m $(MAINTAINER) \
		--license "Apache 2.0" \
		-a $(ARCH) \
		target/apache-jena-fuseki-${FUSEKI_VERSION}/=/opt/hub3/narthex/fuseki/ 
