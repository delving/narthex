# Makefile for Narthex
NAME:=narthex
VERSION:=$(shell sh -c 'grep "version in" version.sbt  | cut -d\" -f2')
MAINTAINER:="Sjoerd Siebinga <sjoerd@delving.eu>"
DESCRIPTION:="Narthex Aggregation and mapping platform."

# var print rule
print-%  : ; @echo $* = $($*)

package:
	sbt package 

dist:
	sbt clean dist

run:
	sbt run

fpm-rpm:
	#make dist
	#unzip -d target/universal target/universal/narthex-$(VERSION).zip
	make fpm-build-rpm ARCH=amd64 FILE_ARCH=x86_64

fpm-build-rpm:
	fpm -s dir -t rpm -n $(NAME) -v $(VERSION) \
		--package $(NAME)-$(VERSION).$(FILE_ARCH).rpm \
		--force \
		-C . \
		--depends java-1.8.0-openjdk \
		--depends blazegraph \
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
