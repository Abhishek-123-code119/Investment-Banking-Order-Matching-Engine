#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# run.sh  —  Run the Order Matching Engine demo
# Usage:
#   ./run.sh              → scripted demo (default)
#   ./run.sh stress       → fire 500 concurrent orders
#   ./run.sh interactive  → manual order entry
# ─────────────────────────────────────────────────────────────────────────────
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$SCRIPT_DIR/out"

if [ ! -d "$OUT" ] || [ -z "$(ls -A "$OUT" 2>/dev/null)" ]; then
  echo "⚠️  Not built yet. Running build.sh first..."
  bash "$SCRIPT_DIR/build.sh"
fi

MODE="${1:-demo}"
echo "🚀 Starting OME in '$MODE' mode..."
echo ""
java -cp "$OUT" com.jpmc.ome.cli.OMEDemo "$MODE"
