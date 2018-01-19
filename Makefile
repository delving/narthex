# Makefile for Narthex
NAME:=narthex
VERSION:=$(shell sh -c 'grep "version in" version.sbt  | cut -d\" -f2')
MAINTAINER:="Sjoerd Siebinga <sjoerd@delving.eu>"
DESCRIPTION:="Narthex Aggregation and mapping platform."
FUSEKI:=apache-fuseki
#FUSEKI_VERSION:=3.4.0
FUSEKI_VERSION:=2.4.1
FUSEKI_VERSION_RPM:=2.4.1-4


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

fpm-build-rpm:
	fpm -s dir -t rpm -n $(NAME) -v $(VERSION) \
		--package $(NAME)-$(VERSION).$(FILE_ARCH).rpm \
		--force \
		-C . \
		--depends java-1.8.0-openjdk \
		--rpm-compression bzip2 --rpm-os linux \
		--url https://bitbucket.org/delving/$(NAME) \
		--description $(DESCRIPTION) \
		-m $(MAINTAINER) \
		--license "Apache 2.0" \
		-a $(ARCH) \
		--before-install deploy/before_install.sh \
		--before-upgrade deploy/before_install.sh \
		--after-install deploy/after_install.sh \
		--after-upgrade deploy/after_install.sh \
		target/universal/narthex-$(VERSION)/=/opt/hub3/narthex/NarthexVersion/ \
		deploy/$(NAME).service=/lib/systemd/system/$(NAME).service \
		deploy/$(NAME).conf=/opt/hub3/narthex/NarthexFiles/narthex.conf \
		deploy/environment.conf=/opt/hub3/narthex/NarthexFiles/environment.conf \
		deploy/logger.xml=/opt/hub3/narthex/NarthexFiles/logger.xml \
		deploy/$(NAME).logrotate=/etc/logrotate.d/$(NAME) \
		deploy/edm_5.2.6_record-definition.xml=/opt/hub3/narthex/NarthexFiles/default/factory/edm/edm_5.2.6_record-definition.xml \
		deploy/edm_5.2.6_validation.xsd=/opt/hub3/narthex/NarthexFiles/default/factory/edm/edm_5.2.6_validation.xsd


fpm-rpm-fuseki:
	rm -rf target/apache-jena-fuseki*
	wget -P target http://archive.apache.org/dist/jena/binaries/apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz
	tar xvzf target/apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz -C target
	mkdir -p target/apache-jena-fuseki-${FUSEKI_VERSION}/run/configuration
	cp deploy/fuseki/narthex.ttl target/apache-jena-fuseki-${FUSEKI_VERSION}/run/configuration
	make fpm-build-fuseki-rpm ARCH=amd64 FILE_ARCH=x86_64


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
		--before-install deploy/fuseki/before_install_fuseki.sh \
		--before-upgrade deploy/fuseki/before_install_fuseki.sh \
		--after-install deploy/fuseki/after_install_fuseki.sh \
		--after-upgrade deploy/fuseki/after_install_fuseki.sh \
		target/apache-jena-fuseki-${FUSEKI_VERSION}/=/opt/hub3/narthex/fuseki/ \
		deploy/fuseki/fuseki.service=/lib/systemd/system/fuseki.service \
		deploy/fuseki/fuseki.conf=/etc/rsyslog.d/ \
		deploy/fuseki/log4j.properties=/opt/hub3/narthex/fuseki/run/ \
