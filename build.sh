#!/usr/bin/env bash
# ============================================================================
# SILLAGE — build with nothing but a JDK.
#
#   ./build.sh            compile the app and the tests into build/
#   ./build.sh jar        also produce build/sillage.jar
#   ./build.sh clean      throw away build/
#
# Needs: JDK 17 or newer on the PATH (javac, java, jar). No network, no Maven,
# no Gradle, no dependencies of any kind.
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

JAVAC="${JAVAC:-javac}"
JAR="${JAR:-jar}"
OUT=build/classes
TEST_OUT=build/test-classes

if [ "${1:-}" = "clean" ]; then
  rm -rf build
  echo "cleaned."
  exit 0
fi

if ! command -v "$JAVAC" >/dev/null 2>&1; then
  echo "error: '$JAVAC' was not found." >&2
  echo "Install a JDK 17 or newer (Debian: apt install openjdk-17-jdk)." >&2
  exit 1
fi

mkdir -p "$OUT" "$TEST_OUT"

echo "sillage: compiling the application"
find src/main/java -name '*.java' | sort > build/sources.txt
"$JAVAC" --release 17 -encoding UTF-8 -Xlint:none -d "$OUT" @build/sources.txt
echo "         $(wc -l < build/sources.txt | tr -d ' ') source files -> $OUT"

echo "sillage: compiling the tests"
find src/test/java -name '*.java' | sort > build/test-sources.txt
"$JAVAC" --release 17 -encoding UTF-8 -Xlint:none -cp "$OUT" -d "$TEST_OUT" @build/test-sources.txt
echo "         $(wc -l < build/test-sources.txt | tr -d ' ') test files -> $TEST_OUT"

if [ "${1:-}" = "jar" ]; then
  echo "sillage: building build/sillage.jar"
  "$JAR" cfe build/sillage.jar com.sillage.SillageApp -C "$OUT" .
  echo "         run it with: java -jar build/sillage.jar"
fi

echo
echo "sillage: build complete."
echo "  start it with   ./run.sh            (demo data is seeded on first boot)"
echo "  run the tests   ./run.sh test"
