#!/bin/sh
touch /opt/hub3/narthex/fuseki/fuseki-syslog.log
chown -R narthex: /opt/hub3/narthex
systemctl daemon-reload 2> /dev/null || true
systemctl enable fuseki 2> /dev/null || true
