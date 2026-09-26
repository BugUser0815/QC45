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
resource_root="$script_dir/src/main/resources"
sources_file="$script_dir/target/sources.txt"
pinned_ecj=${QC45_ECJ_JAR:-"${HOME}/.local/share/qc45-ui-deployer/ecj-3.32.0.jar"}

jar_tool() {
    if command -v jar >/dev/null 2>&1; then
        jar "$@"
    else
        java -m jdk.jartool/sun.tools.jar.Main "$@"
    fi
}

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
    if javac --help 2>&1 | grep -q -- '--release'; then
        javac --release 7 -encoding UTF-8 \
            -classpath "$base_jar" \
            -d "$classes_dir" \
            @"$sources_file"
    else
        javac -source 7 -target 7 -encoding UTF-8 \
            -classpath "$base_jar" \
            -d "$classes_dir" \
            @"$sources_file"
    fi
elif java --list-modules 2>/dev/null | grep -q '^jdk.compiler@'; then
    java -m jdk.compiler/com.sun.tools.javac.Main --release 7 -encoding UTF-8 \
        -classpath "$base_jar" \
        -d "$classes_dir" \
        @"$sources_file"
else
    echo "Neither ecj nor javac was found" >&2
    exit 2
fi

if [ -d "$resource_root" ]; then
    cp -R "$resource_root"/. "$classes_dir"/
fi

cp "$base_jar" "$output_jar"

jar_tool uf "$output_jar" -C "$classes_dir" pt/efacec/es/evcsd/ui

for ui_class in WaitingForCardChargingTimer MainMenuPanel InCCSChargingPanel DtcPanel; do
    class_path="pt/efacec/es/evcsd/ui/$ui_class.class"
    if ! jar_tool tf "$output_jar" | grep -qx "$class_path"; then
        echo "Missing compiled UI class: $class_path" >&2
        exit 1
    fi
done

logo_path="pt/efacec/es/evcsd/ui/sgs-logo.png"
if ! jar_tool tf "$output_jar" | grep -qx "$logo_path"; then
    echo "Missing SGS logo resource: $logo_path" >&2
    exit 1
fi

for connector_image in ccs2 chademo type2; do
    image_path="pt/efacec/es/evcsd/ui/connectors/$connector_image.png"
    if ! jar_tool tf "$output_jar" | grep -qx "$image_path"; then
        echo "Missing connector image resource: $image_path" >&2
        exit 1
    fi
done

echo "$output_jar"
