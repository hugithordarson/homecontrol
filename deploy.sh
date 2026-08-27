#!/bin/zsh
# Deploy HomeControl to the home server (hubuntu, 10.0.1.40).
#
# The server stack (wotaskd + JavaMonitor + modulo) is set up once via
# deploy/install.sh; this script only ships a new build of the app.
# If a JavaMonitor password is ever set, put JM_PW=... in .deploy.env (gitignored).
set -euo pipefail

cd "$(dirname "$0")"

SERVER="hugi@10.0.1.40"
DEPLOY_DIR="/opt/webobjects/apps"
JM="http://10.0.1.40:56789/cgi-bin/WebObjects/JavaMonitor.woa/admin"
APP="HomeControl"

if [ -f .deploy.env ]; then
	source .deploy.env
fi

echo "== Building"
mvn clean package -Dlaunch.jvm=/opt/jdk-26/bin/java
echo "NOTE: maven touched target/classes — if Eclipse is open, recover with parslips (stop app, clean refresh, relaunch)"

echo "== Moving the old .woa aside (kept as $APP.woa.previous for rollback)"
ssh "$SERVER" "rm -rf $DEPLOY_DIR/$APP.woa.previous; [ -d $DEPLOY_DIR/$APP.woa ] && mv $DEPLOY_DIR/$APP.woa $DEPLOY_DIR/$APP.woa.previous || true"

echo "== Copying new .woa to $SERVER:$DEPLOY_DIR"
scp -rq "target/$APP.woa" "$SERVER:$DEPLOY_DIR/"

echo "== Restarting via JavaMonitor"
curl -s "$JM/stop?type=app&name=$APP&pw=${JM_PW:-}" > /dev/null
sleep 5
curl -s "$JM/start?type=app&name=$APP&pw=${JM_PW:-}" > /dev/null

echo "== Waiting for the app to answer through modulo"
for i in {1..12}; do
	sleep 5
	http_status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://10.0.1.40/ || true)
	if [ "$http_status" = "200" ]; then
		echo "HomeControl is up on http://10.0.1.40/ (HTTP 200)"
		exit 0
	fi
	echo "  ...not yet (HTTP $http_status)"
done

echo "App did not come up within a minute — check JavaMonitor at $JM" >&2
exit 1
