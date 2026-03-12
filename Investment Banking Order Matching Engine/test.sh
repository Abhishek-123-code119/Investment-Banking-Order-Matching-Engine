#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# test.sh  —  Run all tests (no JUnit required)
# Usage: ./test.sh
# ─────────────────────────────────────────────────────────────────────────────
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$SCRIPT_DIR/out"

if [ ! -d "$OUT" ] || [ -z "$(ls -A "$OUT" 2>/dev/null)" ]; then
  echo "⚠️  Not built yet. Running build.sh first..."
  bash "$SCRIPT_DIR/build.sh"
fi

echo "🧪 Running tests..."
echo ""
java -cp "$OUT" com.jpmc.ome.TestRunner
