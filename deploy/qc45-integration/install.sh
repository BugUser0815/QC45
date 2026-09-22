#!/usr/bin/env bash
set -Eeuo pipefail

source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
bin_dir="${HOME}/.local/bin"
config_dir="${HOME}/.config/qc45-integration-deployer"
systemd_dir="${HOME}/.config/systemd/user"

for command_name in install systemctl git ssh scp mvn sha256sum awk grep; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "Required command not found: $command_name" >&2
        echo "Install Maven and OpenJDK 21:" >&2
        echo "  sudo apt update && sudo apt install -y openjdk-21-jdk-headless maven" >&2
        exit 2
    fi
done

java21_home=""
for java_home_candidate in /usr/lib/jvm/java-21-openjdk-*; do
    if [[ -x "$java_home_candidate/bin/java" && -x "$java_home_candidate/bin/jar" && -x "$java_home_candidate/bin/javap" ]]; then
        java21_home=$java_home_candidate
        break
    fi
done

if [[ -z "$java21_home" ]]; then
    echo "OpenJDK 21 was not found below /usr/lib/jvm." >&2
    echo "Install it with:" >&2
    echo "  sudo apt update && sudo apt install -y openjdk-21-jdk-headless maven" >&2
    exit 2
fi

java_version=$("$java21_home/bin/java" -version 2>&1 | awk -F'[\".]' '/version/{print $2; exit}')
if [[ "$java_version" != 21 ]]; then
    echo "Expected OpenJDK 21 at $java21_home, got version ${java_version:-unknown}" >&2
    exit 2
fi

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
echo "Using OpenJDK 21 from $java21_home"
echo "Normal manual deployment:"
echo "  $bin_dir/qc45-integration-deploy"
echo
echo "Forced manual deployment:"
echo "  $bin_dir/qc45-integration-force-deploy"
echo
echo "After a successful manual test, enable automatic checks:"
echo "  systemctl --user enable --now qc45-integration-deploy.timer"
