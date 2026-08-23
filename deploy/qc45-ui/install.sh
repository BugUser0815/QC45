#!/usr/bin/env bash
set -Eeuo pipefail

source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
bin_dir="${HOME}/.local/bin"
config_dir="${HOME}/.config/qc45-ui-deployer"
systemd_dir="${HOME}/.config/systemd/user"

for command_name in install systemctl javac javap jar git ssh scp; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "Required command not found: $command_name" >&2
        echo "On Raspberry Pi OS/Debian 12 install JDK 17 with:" >&2
        echo "  sudo apt update && sudo apt install -y openjdk-17-jdk-headless" >&2
        exit 2
    fi
done

compiler_test_dir=$(mktemp -d)
cleanup() {
    rm -rf -- "$compiler_test_dir"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
printf 'final class Java7Check {}\n' >"$compiler_test_dir/Java7Check.java"
if ! javac -source 7 -target 7 -d "$compiler_test_dir" "$compiler_test_dir/Java7Check.java" >/dev/null 2>&1; then
    echo "The installed javac cannot generate Java 7 bytecode." >&2
    echo "Install OpenJDK 17 JDK and run this installer again." >&2
    exit 2
fi

install -d "$bin_dir" "$config_dir" "$systemd_dir"
install -m 0755 "$source_dir/qc45-ui-deploy" "$bin_dir/qc45-ui-deploy"
install -m 0644 "$source_dir/systemd/qc45-ui-deploy.service" "$systemd_dir/qc45-ui-deploy.service"
install -m 0644 "$source_dir/systemd/qc45-ui-deploy.timer" "$systemd_dir/qc45-ui-deploy.timer"

if [[ ! -e "$config_dir/config" ]]; then
    install -m 0600 "$source_dir/config.example" "$config_dir/config"
    echo "Created $config_dir/config"
else
    echo "Keeping existing $config_dir/config"
fi

systemctl --user daemon-reload

echo
echo "Installed the QC45 UI deployer."
echo "Test one deployment first:"
echo "  $bin_dir/qc45-ui-deploy"
echo
echo "After a successful test, enable automatic checks:"
echo "  systemctl --user enable --now qc45-ui-deploy.timer"
