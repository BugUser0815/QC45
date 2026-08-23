#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
base_jar=${1:-}
output_jar=${2:-"$script_dir/target/evcsdUI-qc45-clean-charge-screen.jar"}

if [ -z "$base_jar" ] || [ ! -f "$base_jar" ]; then
    echo "Usage: $0 /path/to/current-evcsdUI.jar [output.jar]" >&2
    exit 2
fi

classes_dir="$script_dir/target/classes"
source_file="$script_dir/src/main/java/pt/efacec/es/evcsd/ui/WaitingForCardChargingTimer.java"

rm -rf "$classes_dir"
mkdir -p "$classes_dir" "$(dirname -- "$output_jar")"

if command -v ecj >/dev/null 2>&1; then
    ecj -1.7 -encoding UTF-8 \
        -classpath "$base_jar" \
        -d "$classes_dir" \
        "$source_file"
elif command -v javac >/dev/null 2>&1; then
    javac -source 7 -target 7 -encoding UTF-8 \
        -classpath "$base_jar" \
        -d "$classes_dir" \
        "$source_file"
else
    echo "Neither ecj nor javac was found" >&2
    exit 2
fi

cp "$base_jar" "$output_jar"

for class_file in "$classes_dir"/pt/efacec/es/evcsd/ui/WaitingForCardChargingTimer*.class; do
    relative=${class_file#"$classes_dir"/}
    jar uf "$output_jar" -C "$classes_dir" "$relative"
done

echo "$output_jar"
