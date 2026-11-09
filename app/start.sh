#!/bin/sh
# (Re)start the web app in the background. Run on every container start; log at /tmp/lumix.log.
cd "$(dirname "$0")/.."
pkill -f 'python3 app/server[.]py' 2>/dev/null
setsid nohup python3 app/server.py > /tmp/lumix.log 2>&1 < /dev/null &
