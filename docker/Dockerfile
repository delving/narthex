FROM dockerfile/java:oracle-java8

MAINTAINER gerald@delving.eu

ENV PKG_NAME narthex-1.0.0-SNAPSHOT
ENV PKG_FILE $PKG_NAME.zip
ENV PKG_PATH "target/universal/$PKG_FILE"

ADD $PKG_PATH /

RUN adduser --disabled-password --gecos '' narthex
RUN cd / && unzip $PKG_FILE && rm $PKG_FILE && mv /$PKG_NAME /narthex

ADD docker/run_narthex.sh /narthex/run_narthex.sh
RUN chmod +x /narthex/run_narthex.sh

RUN mkdir /NarthexFiles && chown narthex:narthex /NarthexFiles
RUN mkdir /NarthexConfig && chown narthex:narthex /NarthexConfig

USER narthex
ADD docker/logger.xml /NarthexConfig/logger.xml
ADD docker/narthex.conf /NarthexConfig/narthex.conf

# Define mountable directories.
VOLUME ["/NarthexFiles"]

ENTRYPOINT /narthex/run_narthex.sh

EXPOSE 9000

