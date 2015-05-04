#!/bin/sh

/narthex/bin/narthex \
    -J-Xmx1024m \
    -J-Xms1024m \
    -J-server \
    -Dapplication.log=/NarthexConfig/narthex_application.log \
    -Dlogger.file=/NarthexConfig/logger.xml \
    -Dhttp.port=9000 \
    -Dpidfile.path=/NarthexConfig/narthex.pid \
    -Dconfig.file=/NarthexConfig/narthex.conf \
    -Dfuseki.host=$FUSEKI_PORT_3030_TCP_ADDR \
    -Dfuseki.port=$FUSEKI_PORT_3030_TCP_PORT

