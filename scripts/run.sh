#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT_DIR/build/mini-cassandra.jar"

if [[ ! -f "$JAR" ]]; then
  "$ROOT_DIR/scripts/build.sh"
fi

java -jar "$JAR"
