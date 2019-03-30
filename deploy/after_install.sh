#!/bin/sh
chown -R narthex: /opt/hub3/narthex
systemctl daemon-reload 2> /dev/null || true
systemctl enable narthex > /dev/null || true
