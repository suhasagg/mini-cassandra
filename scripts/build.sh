#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$ROOT_DIR/build"
MAIN_CLASSES="$BUILD_DIR/classes/main"

rm -rf "$BUILD_DIR"
mkdir -p "$MAIN_CLASSES"

find "$ROOT_DIR/src/main/java" -name '*.java' | sort > "$BUILD_DIR/main-sources.txt"

javac --release 17 -encoding UTF-8 \
  -d "$MAIN_CLASSES" \
  @"$BUILD_DIR/main-sources.txt"

cat > "$BUILD_DIR/MANIFEST.MF" <<'EOF'
Main-Class: com.example.minicassandra.Main
EOF

jar cfm "$BUILD_DIR/mini-cassandra.jar" "$BUILD_DIR/MANIFEST.MF" -C "$MAIN_CLASSES" .

echo "Built $BUILD_DIR/mini-cassandra.jar"
