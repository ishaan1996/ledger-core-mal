#!/usr/bin/env bash
# Compiles main and test sources with nothing but a JDK (21+). No build tool, no network.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/main build/test
javac --release 21 -Xlint:all -Werror -d build/main $(find src/main/java -name '*.java')
javac --release 21 -Xlint:all -Werror -cp build/main -d build/test $(find src/test/java -name '*.java')
