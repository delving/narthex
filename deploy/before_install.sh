#!/bin/sh
mkdir -p /opt/hub3/narthex/NarthexFiles/logs
mkdir -p /opt/hub3/narthex/NarthexFiles/default/factory/edm
/usr/bin/id narthex >/dev/null 2>&1 || /usr/sbin/useradd -U -s /bin/false -c "User for Narthex daemon" -d /opt/hub3/narthex/ narthex 
chown -R narthex: /opt/hub3/narthex
