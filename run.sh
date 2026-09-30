#!/usr/bin/env bash
# Replays the ten-event stream and prints the per-day report. Exits non-zero if any invariant fails.
#   bash run.sh
set -euo pipefail
cd "$(dirname "$0")"
bash build.sh
java -cp build/main ledger.replay.Replay
