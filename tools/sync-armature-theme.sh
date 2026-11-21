#!/usr/bin/env bash
# Pull the generated herald theme from guilnunes/armature (Brander owner, Scriptorium family) into this app.
# Usage: tools/sync-armature-theme.sh [ref]   (default: main)
set -euo pipefail
REF="${1:-main}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
git -C "$TMP" init -q
git -C "$TMP" remote add origin https://github.com/guilnunes/armature
git -C "$TMP" fetch -q --depth 1 origin "$REF"
git -C "$TMP" checkout -q FETCH_HEAD
cp "$TMP/brander/dist/android/herald/ArmatureTheme.kt" "$ROOT/app/src/main/java/digital/brander/theme/herald/ArmatureTheme.kt"
echo "Synced ArmatureTheme.kt from armature@$(git -C "$TMP" rev-parse --short HEAD) (engine $(cat "$TMP/engine/VERSION"))."
