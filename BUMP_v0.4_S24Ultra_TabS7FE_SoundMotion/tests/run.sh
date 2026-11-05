#!/usr/bin/env bash
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
javac -d "$TMP" "$ROOT/app/src/main/java/pt/johnny/bump/ProximityGate.java" "$ROOT/tests/ProximityGateTest.java"
java -cp "$TMP" pt.johnny.bump.ProximityGateTest
python3 "$ROOT/tests/check_assets.py"
