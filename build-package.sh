#!/usr/bin/env bash
#
# build-package.sh - Builds and packages WxSshAdapter for webMethods Integration Server on Linux/macOS
#
# Why this script is shaped the way it is:
#   * Nothing is deployed unless the zip passes a content check, so a broken build cannot replace a good package.
#   * Safe copy / rsync ensures deployed jars are updated cleanly.
#   * Resolves runtime dependencies and Bouncy Castle jars directly through Maven without hardcoded paths.
#
# Environment variables (optional):
#   JAVA_HOME            - Path to JDK (must contain bin/jar and bin/javac).
#   WM_HOME              - Base webMethods install directory (default: /opt/softwareag).
#   WM_PACKAGES          - Destination packages directory (default: $WM_HOME/IntegrationServer/packages/WxSshAdapter).
#   WM_REPLICATE_INBOUND - Inbound replicate directory (default: $WM_HOME/IntegrationServer/replicate/inbound).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Locate Java and jar tool
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jar" ]; then
    JAR_BIN="$JAVA_HOME/bin/jar"
elif command -v jar >/dev/null 2>&1; then
    JAR_BIN="$(command -v jar)"
else
    echo "ERROR: 'jar' utility not found. Please set JAVA_HOME to a valid JDK directory." >&2
    exit 1
fi

WM_HOME="${WM_HOME:-/opt/softwareag}"
WM_PACKAGES="${WM_PACKAGES:-$WM_HOME/IntegrationServer/packages/WxSshAdapter}"
WM_REPLICATE_INBOUND="${WM_REPLICATE_INBOUND:-$WM_HOME/IntegrationServer/replicate/inbound}"

REQUIRED_ZIP_ENTRIES=(
    "manifest.v3"
    "config/WxSshAdapterRegistration.yaml"
    "ns/wm/adapter/wxssh/admin/registerAdapter/node.ndf"
    "ns/wm/adapter/wxssh/admin/unregisterAdapter/node.ndf"
    "ns/wm/adapter/wxssh/admin/getHostKeyFingerprint/node.ndf"
    "ns/wm/adapter/wxssh/admin/addToKnownHosts/node.ndf"
    "pub/index.html"
    "code/classes/com/wm/adapter/wxssh/WxSshAdapter.class"
    "code/classes/com/wm/adapter/wxssh/connection/SshConnectionFactory.class"
    "code/classes/com/wm/adapter/wxssh/connection/SshManagedConnection.class"
    "code/classes/com/wm/adapter/wxssh/service/ExecCommandService.class"
    "code/classes/com/luisloja/wxssh/core/SshSession.class"
)

REQUIRED_JAR_PREFIXES=("sshj-" "asn-one-" "bcprov-" "bcpkix-")

echo ">>> Compiling wx-ssh-adapter..."
mvn -B clean compile

echo ">>> Copying runtime dependencies..."
mvn -B dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/runtime-jars
mvn -B dependency:copy-dependencies "-DincludeArtifactIds=bcprov-jdk18on,bcpkix-jdk18on" -DoutputDirectory=target/bc-jars

PKG_DIR="$SCRIPT_DIR/target/package/WxSshAdapter"
rm -rf "$PKG_DIR"
mkdir -p "$PKG_DIR/code/classes" "$PKG_DIR/code/jars"

echo ">>> Copying template files..."
cp -R "$SCRIPT_DIR/package-template/"* "$PKG_DIR/"

echo ">>> Copying compiled classes..."
cp -R "$SCRIPT_DIR/target/classes/"* "$PKG_DIR/code/classes/"

echo ">>> Copying jar dependencies..."
cp "$SCRIPT_DIR"/target/runtime-jars/sshj-*.jar "$PKG_DIR/code/jars/"
cp "$SCRIPT_DIR"/target/runtime-jars/asn-one-*.jar "$PKG_DIR/code/jars/"
cp "$SCRIPT_DIR"/target/bc-jars/bcprov-*.jar "$PKG_DIR/code/jars/"
cp "$SCRIPT_DIR"/target/bc-jars/bcpkix-*.jar "$PKG_DIR/code/jars/"

echo ">>> Creating WxSshAdapter.zip..."
ZIP_PATH="$SCRIPT_DIR/target/WxSshAdapter.zip"
ROOT_ZIP_PATH="$SCRIPT_DIR/WxSshAdapter.zip"
rm -f "$ZIP_PATH" "$ROOT_ZIP_PATH"

(cd "$PKG_DIR" && "$JAR_BIN" cfM "$ZIP_PATH" .)
cp "$ZIP_PATH" "$ROOT_ZIP_PATH"
echo "    Saved to $ROOT_ZIP_PATH"

echo ">>> Checking the zip content..."
ZIP_LIST="$("$JAR_BIN" tf "$ZIP_PATH")"

MISSING_ENTRIES=()
for entry in "${REQUIRED_ZIP_ENTRIES[@]}"; do
    if ! echo "$ZIP_LIST" | grep -F -q "$entry"; then
        MISSING_ENTRIES+=("$entry")
    fi
done

for prefix in "${REQUIRED_JAR_PREFIXES[@]}"; do
    if ! echo "$ZIP_LIST" | grep -q "^code/jars/${prefix}.*\.jar$"; then
        MISSING_ENTRIES+=("code/jars/${prefix}*.jar")
    fi
done

if [ ${#MISSING_ENTRIES[@]} -ne 0 ]; then
    echo "ERROR: WxSshAdapter.zip is incomplete! Missing entries:" >&2
    for m in "${MISSING_ENTRIES[@]}"; do
        echo "  - $m" >&2
    done
    exit 1
fi

TOTAL_ENTRIES="$(echo "$ZIP_LIST" | grep -v '/$' | wc -l | tr -d ' ')"
echo "    $TOTAL_ENTRIES entries verified, all required files and JARs present."

if [ -d "$(dirname "$WM_PACKAGES")" ]; then
    echo ">>> Deploying package to webMethods Integration Server ($WM_PACKAGES)..."
    mkdir -p "$WM_PACKAGES" "$WM_REPLICATE_INBOUND"
    cp "$ZIP_PATH" "$WM_REPLICATE_INBOUND/WxSshAdapter.zip"
    if command -v rsync >/dev/null 2>&1; then
        rsync -a "$PKG_DIR/" "$WM_PACKAGES/"
    else
        cp -R "$PKG_DIR/"* "$WM_PACKAGES/"
    fi
    echo "    Copied to $WM_REPLICATE_INBOUND/WxSshAdapter.zip"
    echo "    Deployed to $WM_PACKAGES"
else
    echo ">>> Info: Target directory $(dirname "$WM_PACKAGES") does not exist on this machine."
    echo "    Package artifact is ready at: $ROOT_ZIP_PATH"
fi

echo ">>> Packaging complete!"
