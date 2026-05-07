#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$ROOT_DIR/build"
MAIN_CLASSES="$BUILD_DIR/classes/main"
TEST_CLASSES="$BUILD_DIR/classes/test"

"$ROOT_DIR/scripts/build.sh"
mkdir -p "$TEST_CLASSES"

find "$ROOT_DIR/src/test/java" -name '*.java' | sort > "$BUILD_DIR/test-sources.txt"

javac --release 17 -encoding UTF-8 \
  -cp "$MAIN_CLASSES" \
  -d "$TEST_CLASSES" \
  @"$BUILD_DIR/test-sources.txt"

TESTS=(
  com.example.minicassandra.BloomFilterTest
  com.example.minicassandra.ConsistentHashRingTest
  com.example.minicassandra.LsmStorageEngineTest
  com.example.minicassandra.MiniCassandraClusterTest
)

for test in "${TESTS[@]}"; do
  java -cp "$MAIN_CLASSES:$TEST_CLASSES" "$test"
done

echo "All tests passed."
