#!/usr/bin/env bash
set -Eeuo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
config_dir="${HOME}/.config/qc45-ui-deployer"
state_dir="${HOME}/.local/state/qc45-ui-deployer"
bin_dir="${HOME}/.local/bin"
share_dir="${HOME}/.local/share/qc45-ui-deployer"
systemd_dir="${HOME}/.config/systemd/user"
config_file="$config_dir/config"
ecj_jar="$share_dir/ecj-3.32.0.jar"
ecj_url="https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.32.0/ecj-3.32.0.jar"
ecj_sha256="07e034c44a019c0c6394a06ee7b5c344e5518f6083c0fd046f2d8fd16a6760e2"

required_commands=(curl java javap sha256sum install systemctl grep awk mktemp)
for command_name in "${required_commands[@]}"; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "Required command not found: $command_name" >&2
        exit 2
    fi
done

mkdir -p "$config_dir" "$state_dir" "$bin_dir" "$share_dir" "$systemd_dir"
chmod 700 "$config_dir" "$state_dir" "$share_dir"

if [[ ! -r "$config_file" ]]; then
    install -m 600 "$script_dir/config.example" "$config_file"
    echo "Created $config_file"
    echo "Review it before the first deployment."
fi

if [[ ! -r "$ecj_jar" ]] || [[ "$(sha256sum "$ecj_jar" | awk '{print $1}')" != "$ecj_sha256" ]]; then
    tmp_jar=$(mktemp "$share_dir/ecj.XXXXXXXX.jar")
    trap 'rm -f -- "$tmp_jar"' EXIT
    echo "Downloading pinned ECJ compiler 3.32.0..."
    curl --fail --location --silent --show-error "$ecj_url" -o "$tmp_jar"
    actual_sha=$(sha256sum "$tmp_jar" | awk '{print $1}')
    if [[ "$actual_sha" != "$ecj_sha256" ]]; then
        echo "ECJ checksum mismatch: $actual_sha" >&2
        exit 1
    fi
    mv -f "$tmp_jar" "$ecj_jar"
    chmod 600 "$ecj_jar"
    trap - EXIT
fi

compiler_test_dir=$(mktemp -d "$state_dir/compiler-test.XXXXXXXX")
trap 'rm -rf -- "$compiler_test_dir"' EXIT
cat >"$compiler_test_dir/Java7Check.java" <<'JAVA'
final class Java7Check {}
JAVA
java -jar "$ecj_jar" -proc:none -1.7 -d "$compiler_test_dir" "$compiler_test_dir/Java7Check.java"
if ! javap -verbose -classpath "$compiler_test_dir" Java7Check | grep -Fq 'major version: 51'; then
    echo "Pinned ECJ did not produce Java 7 bytecode" >&2
    exit 1
fi
rm -rf -- "$compiler_test_dir"
trap - EXIT

install -m 700 "$script_dir/qc45-ui-deploy" "$bin_dir/qc45-ui-deploy"
install -m 700 "$script_dir/qc45-ui-force-deploy" "$bin_dir/qc45-ui-force-deploy"
install -m 644 "$script_dir/systemd/qc45-ui-deploy.service" "$systemd_dir/qc45-ui-deploy.service"
install -m 644 "$script_dir/systemd/qc45-ui-deploy.timer" "$systemd_dir/qc45-ui-deploy.timer"

systemctl --user daemon-reload

echo "Installed:"
echo "  $bin_dir/qc45-ui-deploy"
echo "  $bin_dir/qc45-ui-force-deploy"
echo "  $systemd_dir/qc45-ui-deploy.service"
echo "  $systemd_dir/qc45-ui-deploy.timer"
echo ""
echo "Manual normal deployment:"
echo "  $bin_dir/qc45-ui-deploy"
echo ""
echo "Manual forced deployment (ignores matching deployed tree):"
echo "  $bin_dir/qc45-ui-force-deploy"
echo ""
echo "After a successful manual test, enable the timer with:"
echo "  systemctl --user enable --now qc45-ui-deploy.timer"
