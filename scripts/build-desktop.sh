#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# Build script for Billery Desktop (Tauri + Spring Boot + H2)
# Produces platform-specific installer:
#   Windows: .exe (NSIS) or .msi
#   Linux:   .AppImage
#   macOS:   .dmg
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$ROOT_DIR/backend"
FRONTEND_DIR="$ROOT_DIR/frontend"
TAURI_DIR="$ROOT_DIR/app-desktop"
RESOURCES_DIR="$TAURI_DIR/resources"

echo "=== Building Billery Desktop ==="

# Step 1: Build Vue frontend
echo ""
echo "--- Step 1: Building frontend ---"
cd "$FRONTEND_DIR"
npm ci --legacy-peer-deps
npm run build

# Step 1.5: Copy frontend into Spring Boot static resources
echo ""
echo "--- Step 1.5: Copying frontend into backend ---"
mkdir -p "$BACKEND_DIR/src/main/resources/static"
rm -rf "$BACKEND_DIR/src/main/resources/static/"*
cp -r "$FRONTEND_DIR/dist/"* "$BACKEND_DIR/src/main/resources/static/"

# Step 2: Build Spring Boot backend with desktop profile
echo ""
echo "--- Step 2: Building backend (desktop profile) ---"
cd "$BACKEND_DIR"
mvn clean package -Pdesktop -DskipTests

# Step 3: Create minimal JRE via jlink
echo ""
echo "--- Step 3: Creating minimal JRE via jlink ---"
JAR_FILE="$BACKEND_DIR/target/backend-1.0-SNAPSHOT.jar"
JLINK_OUTPUT="$BACKEND_DIR/target/runtime"

if [ ! -f "$JAR_FILE" ]; then
    echo "ERROR: JAR not found at $JAR_FILE"
    exit 1
fi

# Discover required modules
echo "  Discovering required modules..."
MODULES=$(jdeps --ignore-missing-deps --print-module-deps "$JAR_FILE" 2>/dev/null || true)
MODULES="${MODULES},java.desktop,java.naming,java.management,java.instrument,jdk.unsupported,java.scripting,java.security.jgss,java.security.sasl"

echo "  Modules: $MODULES"

# The bundled JRE must run the JAR: the backend is compiled for Java 25 (pom <release>), so a
# jlink from an older JDK would produce a runtime that cannot start it.
REQUIRED_JAVA=25
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
if [ "$JAVA_MAJOR" != "$REQUIRED_JAVA" ]; then
    echo "ERROR: building the desktop runtime needs JDK $REQUIRED_JAVA (found: ${JAVA_MAJOR:-unknown}). Set JAVA_HOME." >&2
    exit 1
fi

# Find jmods path
JAVA_HOME="${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(which java))))}"
JMODS_PATH="$JAVA_HOME/jmods"

if [ ! -d "$JMODS_PATH" ]; then
    echo "  jmods not found at $JMODS_PATH, trying alternate locations..."
    # Try to find jmods
    POTENTIAL_PATHS=(
        "$JAVA_HOME/jmods"
        "$JAVA_HOME/../jmods"
        "/usr/lib/jvm/java-25-openjdk-amd64/jmods"
        "/usr/lib/jvm/java-25-oracle/jmods"
    )
    for p in "${POTENTIAL_PATHS[@]}"; do
        if [ -d "$p" ]; then
            JMODS_PATH="$p"
            echo "  Found jmods at: $JMODS_PATH"
            break
        fi
    done
fi

rm -rf "$JLINK_OUTPUT"
jlink --module-path "$JMODS_PATH" \
      --add-modules "$MODULES" \
      --output "$JLINK_OUTPUT" \
      --strip-debug \
      --no-header-files \
      --no-man-pages \
      --compress=2

echo "  JRE created at: $JLINK_OUTPUT ($(du -sh "$JLINK_OUTPUT" | cut -f1))"

# Step 4: Copy JAR and JRE into Tauri resources
echo ""
echo "--- Step 4: Copying artifacts to Tauri resources ---"
rm -rf "$RESOURCES_DIR"
mkdir -p "$RESOURCES_DIR"
cp "$JAR_FILE" "$RESOURCES_DIR/app.jar"
cp -r "$JLINK_OUTPUT" "$RESOURCES_DIR/runtime"

# jlink creates read-only legal files — make them writable so Tauri can copy them
chmod -R u+w "$RESOURCES_DIR/runtime/legal/" 2>/dev/null || true
# Remove unnecessary files from runtime to reduce size
find "$RESOURCES_DIR/runtime" -name "*.diz" -delete 2>/dev/null || true
find "$RESOURCES_DIR/runtime" -name "src.zip" -delete 2>/dev/null || true

echo "  Resources prepared at: $RESOURCES_DIR"

# Step 4.5: Install Tauri CLI
echo ""
echo "--- Step 4.5: Installing Tauri CLI ---"
cd "$TAURI_DIR"
if [ ! -d node_modules ]; then
  npm install --legacy-peer-deps
else
  echo "  node_modules already present, skipping npm install"
fi

# Step 5: Build Tauri app
echo ""
echo "--- Step 5: Building Tauri app ---"
cd "$TAURI_DIR"
npx tauri build -b "$@"

echo ""
echo "=== Build complete! ==="
echo "Check $TAURI_DIR/target/release/bundle/ for the installer."
