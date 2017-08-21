# Makefile for Narthex
NAME:="narthex"
VERSION:=$(shell sh -c 'grep "version in" version.sbt  | cut -d\" -f2')
MAINTAINER:="Sjoerd Siebinga <sjoerd@delving.eu>"
DESCRIPTION:="Narthex Aggregation and mapping platform."

# var print rule
print-%  : ; @echo $* = $($*)

package:
	sbt package 

run:
	sbt run

fpm-rpm:
	make package
	unzip target/universal/narthex-$(VERSION).zip
	make fpm-build-rpm ARCH=amd64 FILE_ARCH=x86_64

fpm-build-rpm:
	fpm -s dir -t rpm -n $(NAME) -v $(VERSION) \
		--package $(NAME)-$(VERSION)-1.$(FILE_ARCH).rpm \
		--force \
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
		target/universal/narthex-$(VERSION)=/var/lib/narthex
		deploy/$(NAME).service=/lib/systemd/system/$(NAME).service \
		deploy/$(NAME).defaults=/etc/default/$(NAME) \
		d}eploy/$(NAME).logrotate=/etc/logrotate.d/$(NAME) \
		d}eploy/logger.xml=/opt/hub3/$(NAME)=/var/lib/narthex/conf/ 
