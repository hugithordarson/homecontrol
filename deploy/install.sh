#!/bin/bash
# One-time server setup for HomeControl's WO deployment stack on hubuntu.
# Everything is staged in ~hugi/wo-stage by deploy-time tooling; this script
# just needs root to place it and register the systemd services.
#
# Run as: sudo bash /home/hugi/wo-stage/install.sh
set -euo pipefail

STAGE=/home/hugi/wo-stage

# Bundles are built for /opt/jdk-26 (hz1 convention); point it at the JDK we have
[ -e /opt/jdk-26 ] || ln -s /opt/jdk-26.0.2 /opt/jdk-26

mkdir -p /opt/webobjects/apps /opt/webobjects/conf /opt/webobjects/log

cp -r "$STAGE/apps/." /opt/webobjects/apps/
cp "$STAGE/conf/SiteConfig.xml" /opt/webobjects/conf/
chown -R hugi:hugi /opt/webobjects

cp "$STAGE"/units/*.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now wotaskd javamonitor modulo

echo "== services:"
systemctl --no-pager --plain --lines=0 status wotaskd javamonitor modulo | grep -E "\.service|Active:"
