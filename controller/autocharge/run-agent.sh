#!/bin/sh
# Keep the observer alive independently of EVACom and its power-limit preload.
set -u
cd /home/qc45-autocharge || exit 1
child=
stop() {
    trap '' TERM INT
    if [ -n "$child" ]; then
        kill -TERM "$child" 2>/dev/null || :
        wait "$child" 2>/dev/null || :
    fi
    exit 0
}
trap stop TERM INT
while :; do
    /usr/bin/python /home/qc45-autocharge/autocharge_agent.py --config /home/qc45-autocharge/agent.json &
    child=$!
    wait "$child" || :
    child=
    sleep 5 &
    child=$!
    wait "$child" || :
    child=
done
