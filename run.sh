#!/usr/bin/env bash
# ============================================================================
# SILLAGE — build (if needed) and run.
#
#   ./run.sh                 start on :8080, seeding the demo salon on first boot
#   ./run.sh test            run the test suite
#   ./run.sh --port=9000     any flag is passed to the app (see ./run.sh --help)
#   ./run.sh reset           wipe ./data and reseed
#
# Environment: SILLAGE_PORT, SILLAGE_HOST, SILLAGE_DATA, SILLAGE_WEB,
#              SILLAGE_LOG, SILLAGE_SEED, SILLAGE_DEV ...
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

JAVA="${JAVA:-java}"

if [ ! -d build/classes ] || [ -z "$(ls -A build/classes 2>/dev/null)" ]; then
  ./build.sh
fi

if [ "${1:-}" = "test" ]; then
  exec "$JAVA" -cp build/classes:build/test-classes com.sillage.test.TestSuite
fi

if [ "${1:-}" = "reset" ]; then
  shift
  exec "$JAVA" -cp build/classes com.sillage.SillageApp --reset "$@"
fi

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-help" ]; then
  exec "$JAVA" -cp build/classes com.sillage.SillageApp --help
fi

exec "$JAVA" -cp build/classes com.sillage.SillageApp "$@"
