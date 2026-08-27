#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
target_dir="$script_dir/target/test"
stub_classes="$target_dir/stub-classes"
runner_classes="$target_dir/runner-classes"
stub_sources="$target_dir/stub-sources.txt"
preview_dir="$target_dir/previews"
base_jar="$target_dir/evcsd-ui-test-base.jar"
patched_jar="$target_dir/evcsd-ui-test-patched.jar"
native_modbus="$script_dir/../native-integration/src/main/java/de/rothner/qc45/ModbusServer.java"

if [ -r "$native_modbus" ]; then
    grep -q 'UI_BALANCING_FIRST_REGISTER = 126;' "$native_modbus"
    grep -q 'UI_BALANCING_REGISTER_COUNT = 20;' "$native_modbus"
    grep -q 'UI_BALANCING_VERSION = 1;' "$native_modbus"
fi

compile_java7() {
    destination=$1
    classpath=$2
    sources=$3
    if command -v javac >/dev/null 2>&1; then
        javac --release 7 -encoding UTF-8 -classpath "$classpath" -d "$destination" @"$sources"
    else
        java -m jdk.compiler/com.sun.tools.javac.Main --release 7 -encoding UTF-8 \
            -classpath "$classpath" -d "$destination" @"$sources"
    fi
}

jar_tool() {
    if command -v jar >/dev/null 2>&1; then
        jar "$@"
    else
        java -m jdk.jartool/sun.tools.jar.Main "$@"
    fi
}

rm -rf "$target_dir"
mkdir -p "$stub_classes" "$runner_classes" "$preview_dir"
find "$script_dir/src/test/java" -type f -name '*.java' ! -name 'UiPatchTest.java' \
    | sort > "$stub_sources"
compile_java7 "$stub_classes" "$stub_classes" "$stub_sources"
jar_tool cf "$base_jar" -C "$stub_classes" .

QC45_ECJ_JAR=/does/not/exist "$script_dir/build.sh" "$base_jar" "$patched_jar" >/dev/null

runner_sources="$target_dir/runner-sources.txt"
find "$script_dir/src/test/java" -type f -name 'UiPatchTest.java' > "$runner_sources"
compile_java7 "$runner_classes" "$patched_jar" "$runner_sources"

java -Djava.awt.headless=true -cp "$patched_jar:$runner_classes" \
    pt.efacec.es.evcsd.ui.UiPatchTest \
    "$preview_dir/ac-dc-load-balancing.png" \
    "$preview_dir/ac-dc-failback.png" \
    "$preview_dir/ac-dc-configuration-block.png" \
    "$preview_dir/ac-dc-limit-mismatch.png" \
    "$preview_dir/connector-selection.png" \
    "$preview_dir/parallel-connector-selection.png"

class_header=$(od -An -t u1 -j 6 -N 2 \
    "$script_dir/target/classes/pt/efacec/es/evcsd/ui/WaitingForCardChargingTimer.class")
set -- $class_header
major=$(( $1 * 256 + $2 ))
if [ "$major" -ne 51 ]; then
    echo "Unexpected class major version: $major" >&2
    exit 1
fi

echo "Java class major: $major"
echo "$preview_dir/ac-dc-load-balancing.png"
echo "$preview_dir/ac-dc-failback.png"
echo "$preview_dir/ac-dc-configuration-block.png"
echo "$preview_dir/ac-dc-limit-mismatch.png"
echo "$preview_dir/connector-selection.png"
echo "$preview_dir/parallel-connector-selection.png"
