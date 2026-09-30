#!/usr/bin/env bash
# Runs the test suite, then the replay as a smoke test. Exits non-zero on any failure.
#   bash test.sh
set -euo pipefail
cd "$(dirname "$0")"
bash build.sh
java -cp build/main:build/test ledger.LedgerTests
java -cp build/main ledger.replay.Replay > /dev/null && echo "replay: all invariants ok"
