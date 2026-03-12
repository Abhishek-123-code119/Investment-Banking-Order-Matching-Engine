#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# build.sh  —  Compile the Order Matching Engine (no Maven / no Gradle)
# Usage: ./build.sh
# ─────────────────────────────────────────────────────────────────────────────
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$SCRIPT_DIR/src"
TEST="$SCRIPT_DIR/test"
OUT="$SCRIPT_DIR/out"

echo "🔨 Compiling sources..."
mkdir -p "$OUT"

# Compile all source files in one pass
find "$SRC" -name "*.java" | xargs javac -d "$OUT" -encoding UTF-8

echo "🔨 Compiling test sources (classpath includes compiled src)..."
find "$TEST" -name "*.java" | xargs javac -cp "$OUT" -d "$OUT" -encoding UTF-8

echo "✅ Build successful. Classes written to: out/"
