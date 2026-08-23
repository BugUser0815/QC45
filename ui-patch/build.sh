#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
base_jar=${1:-}
output_jar=${2:-"$script_dir/target/evcsdUI-qc45-alpitronic-ui.jar"}

if [ -z "$base_jar" ] || [ ! -f "$base_jar" ]; then
    echo "Usage: $0 /path/to/current-evcsdUI.jar [output.jar]" >&2
    exit 2
fi

classes_dir="$script_dir/target/classes"
source_root="$script_dir/src/main/java"
sources_file="$script_dir/target/sources.txt"
pinned_ecj=${QC45_ECJ_JAR:-"${HOME}/.local/share/qc45-ui-deployer/ecj-3.32.0.jar"}

rm -rf "$classes_dir"
mkdir -p "$classes_dir" "$(dirname -- "$output_jar")"
find "$source_root" -type f -name '*.java' | sort > "$sources_file"

if [ -r "$pinned_ecj" ] && command -v java >/dev/null 2>&1; then
    java -jar "$pinned_ecj" -proc:none -1.7 -encoding UTF-8 \
        -classpath "$base_jar" \
        -d "$classes_dir" \
        @"$sources_file"
elif command -v ecj >/dev/null 2>&1; then
    ecj -proc:none -1.7 -encoding UTF-8 \
        -classpath "$base_jar" \
        -d "$classes_dir" \
        @"$sources_file"
elif command -v javac >/dev/null 2>&1; then
    javac -source 7 -target 7 -encoding UTF-8 \
        -classpath "$base_jar" \
        -d "$classes_dir" \
        @"$sources_file"
else
    echo "Neither ecj nor javac was found" >&2
    exit 2
fi

cp "$base_jar" "$output_jar"

jar uf "$output_jar" -C "$classes_dir" pt/efacec/es/evcsd/ui

for ui_class in WaitingForCardChargingTimer MainMenuPanel InCCSChargingPanel DtcPanel; do
    class_path="pt/efacec/es/evcsd/ui/$ui_class.class"
    if ! jar tf "$output_jar" | grep -qx "$class_path"; then
        echo "Missing compiled UI class: $class_path" >&2
        exit 1
    fi
done

echo "$output_jar"
