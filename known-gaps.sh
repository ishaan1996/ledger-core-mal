#!/usr/bin/env bash
# Runs the tests that FAIL against the design as built (see KNOWN_GAPS.md). Kept out of test.sh
# on purpose. Exits non-zero while any gap is open.
#   bash known-gaps.sh
set -euo pipefail
cd "$(dirname "$0")"
bash build.sh
mkdir -p build/gaps
javac --release 21 -Xlint:all -Werror -cp build/main:build/test -d build/gaps $(find src/gaps/java -name '*.java')
java -cp build/main:build/test:build/gaps ledger.KnownGaps
