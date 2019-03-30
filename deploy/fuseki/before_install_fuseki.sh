#!/bin/sh
mkdir -p /opt/hub3/narthex/fuseki/run/configuration
mkdir -p /opt/hub3/narthex/fuseki/run/databases/narthex
/usr/bin/id narthex >/dev/null 2>&1 || /usr/sbin/useradd -U -s /bin/false -c "User for Narthex daemon" -d /opt/hub3/narthex/ narthex 
chown -R narthex: /opt/hub3/narthex/fuseki
