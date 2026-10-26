#!/bin/sh
# Run on EVAcharge SE with a prepared agent.json and shared observer.key.
set -eu
[ "$(id -u)" = 0 ] || { echo 'Run as root on EVAcharge SE' >&2; exit 1; }
[ -f /home/evacom/EVACom_Evse_Gui ] || { echo 'EVAcharge application not found' >&2; exit 1; }
[ -x /usr/bin/python ] && [ -x /usr/sbin/tcpdump ]
[ -x /sbin/start-stop-daemon ] && [ -x /usr/sbin/update-rc.d ]
[ "$#" = 1 ] || { echo "Usage: $0 /absolute/path/to/prepared-config-directory" >&2; exit 1; }
case "$1" in /*) ;; *) echo 'Configuration directory must be absolute' >&2; exit 1;; esac
config=$1
base=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
test -r "$config/agent.json" && test -r "$config/observer.key"
if /etc/init.d/qc45-autocharge-agent status >/dev/null 2>&1; then
    echo 'Stop the existing observer before updating it' >&2; exit 1
fi
mkdir -p /home/qc45-autocharge
chmod 700 /home/qc45-autocharge
install -m 644 "$base/autocharge_agent.py" /home/qc45-autocharge/autocharge_agent.py
install -m 755 "$base/run-agent.sh" /home/qc45-autocharge/run-agent.sh
if [ "$config" != /home/qc45-autocharge ]; then
    install -m 600 "$config/agent.json" /home/qc45-autocharge/agent.json
    install -m 600 "$config/observer.key" /home/qc45-autocharge/observer.key
fi
/usr/bin/python /home/qc45-autocharge/autocharge_agent.py --config /home/qc45-autocharge/agent.json --check-config
install -m 755 "$base/qc45-autocharge-agent.init" /etc/init.d/qc45-autocharge-agent
/usr/sbin/update-rc.d qc45-autocharge-agent defaults
echo 'Observer installed for boot. Start after the Geode receiver is configured:'
echo '/etc/init.d/qc45-autocharge-agent start'
