#!/usr/bin/env bash
set -Eeuo pipefail

source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
bin_dir="${HOME}/.local/bin"
config_dir="${HOME}/.config/qc45-integration-deployer"
systemd_dir="${HOME}/.config/systemd/user"

for command_name in install systemctl git ssh scp mvn java jar javap sha256sum awk grep; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "Required command not found: $command_name" >&2
        echo "Install Maven and a JDK capable of compiling Java 7 bytecode, preferably OpenJDK 17:" >&2
        echo "  sudo apt update && sudo apt install -y openjdk-17-jdk-headless maven" >&2
        exit 2
    fi
done

install -d "$bin_dir" "$config_dir" "$systemd_dir"
install -m 0755 "$source_dir/qc45-integration-deploy" "$bin_dir/qc45-integration-deploy"
install -m 0755 "$source_dir/qc45-integration-force-deploy" "$bin_dir/qc45-integration-force-deploy"
install -m 0644 "$source_dir/systemd/qc45-integration-deploy.service" "$systemd_dir/qc45-integration-deploy.service"
install -m 0644 "$source_dir/systemd/qc45-integration-deploy.timer" "$systemd_dir/qc45-integration-deploy.timer"

if [[ ! -e "$config_dir/config" ]]; then
    install -m 0600 "$source_dir/config.example" "$config_dir/config"
    echo "Created $config_dir/config"
else
    echo "Keeping existing $config_dir/config"
fi

systemctl --user daemon-reload

echo
echo "Installed the QC45 integration deployer."
echo "Normal manual deployment:"
echo "  $bin_dir/qc45-integration-deploy"
echo
echo "Forced manual deployment:"
echo "  $bin_dir/qc45-integration-force-deploy"
echo
echo "After a successful manual test, enable automatic checks:"
echo "  systemctl --user enable --now qc45-integration-deploy.timer"
