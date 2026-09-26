#!/usr/bin/env bash
set -Eeuo pipefail

source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
bin_dir="${HOME}/.local/bin"
config_dir="${HOME}/.config/qc45-ui-deployer"
systemd_dir="${HOME}/.config/systemd/user"
compiler_dir="${HOME}/.local/share/qc45-ui-deployer"
ecj_jar="$compiler_dir/ecj-3.32.0.jar"
ecj_url="https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.32.0/ecj-3.32.0.jar"
ecj_sha256="07e034c44a019c0c6394a06ee7b5c344e5518f6083c0fd046f2d8fd16a6760e2"
compiler_test_dir=""
ecj_download=""

cleanup() {
    if [[ -n "$compiler_test_dir" && -d "$compiler_test_dir" ]]; then
        rm -rf -- "$compiler_test_dir"
    fi
    if [[ -n "$ecj_download" && -f "$ecj_download" ]]; then
        rm -f -- "$ecj_download"
    fi
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

for command_name in install systemctl java javap jar git ssh scp curl sha256sum awk grep; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "Required command not found: $command_name" >&2
        echo "Install OpenJDK 21 and curl with:" >&2
        echo "  sudo apt update && sudo apt install -y openjdk-21-jdk-headless curl" >&2
        exit 2
    fi
done

install -d "$compiler_dir"
installed_ecj_sha256=""
if [[ -r "$ecj_jar" ]]; then
    installed_ecj_sha256=$(sha256sum "$ecj_jar" | awk '{print $1}')
fi

if [[ "$installed_ecj_sha256" != "$ecj_sha256" ]]; then
    ecj_download=$(mktemp "$compiler_dir/ecj-3.32.0.jar.download.XXXXXXXX")
    echo "Downloading pinned ECJ 3.32.0 compiler..."
    curl --fail --location --silent --show-error --output "$ecj_download" "$ecj_url"
    downloaded_ecj_sha256=$(sha256sum "$ecj_download" | awk '{print $1}')
    if [[ "$downloaded_ecj_sha256" != "$ecj_sha256" ]]; then
        echo "Downloaded ECJ checksum mismatch" >&2
        rm -f -- "$ecj_download"
        exit 2
    fi
    mv -f "$ecj_download" "$ecj_jar"
fi

compiler_test_dir=$(mktemp -d)
printf 'final class Java7Check {}\n' >"$compiler_test_dir/Java7Check.java"
if ! java -jar "$ecj_jar" -proc:none -1.7 \
    -d "$compiler_test_dir" "$compiler_test_dir/Java7Check.java" >/dev/null 2>&1; then
    echo "The pinned ECJ compiler cannot generate Java 7 bytecode." >&2
    exit 2
fi

if ! javap -verbose -classpath "$compiler_test_dir" Java7Check \
    | grep -q 'major version: 51'; then
    echo "ECJ generated an unexpected class-file version." >&2
    exit 2
fi

install -d "$bin_dir" "$config_dir" "$systemd_dir"
install -m 0755 "$source_dir/qc45-ui-deploy" "$bin_dir/qc45-ui-deploy"
install -m 0755 "$source_dir/qc45-ui-force-deploy" "$bin_dir/qc45-ui-force-deploy"
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
echo "Normal manual deployment:"
echo "  $bin_dir/qc45-ui-deploy"
echo
echo "Forced manual deployment (always rebuilds and reinstalls):"
echo "  $bin_dir/qc45-ui-force-deploy"
echo
echo "After a successful test, enable automatic checks:"
echo "  systemctl --user enable --now qc45-ui-deploy.timer"
