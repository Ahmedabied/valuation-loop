#!/usr/bin/env bash
# make demo-reset: clean numbers for a demo you will show someone. Stops the demo app (and a split run) if this repo's
# scripts started them, stops Kafka and Postgres, then deletes their data directories and the replay snapshot. The
# next make demo re-initialises both (Postgres initdb + Flyway V1-V5, Kafka format --standalone).
# Kept: the Elasticsearch data (the comparables index, rebuilt only by make load) and the Postgres password file.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh

: "${KAFKA_DATA:?KAFKA_DATA not set}" "${PG_DATA:?PG_DATA not set}"
for dir in "$KAFKA_DATA" "$PG_DATA"; do   # never let a bad variable point rm -rf at something broad
  case "$dir" in
    /|"$HOME"|"$HOME/"|"$HOME/tools"|"$HOME/tools/") echo "refusing to delete $dir" >&2; exit 1 ;;
  esac
done

# 1. the apps this repo's scripts started (by pidfile only; never anything else)
if app_pid >/dev/null; then stop_app; else echo "demo app not running"; fi
if [ -f "$LOGS/api.pid" ] || [ -f "$LOGS/worker.pid" ]; then scripts/split.sh stop; fi

# 2. the services that own the two directories
make --no-print-directory kafka-down || true
if [ -f "$PG_DATA/postmaster.pid" ]; then make --no-print-directory pg-down || true; else echo "postgres not running"; fi

# 3. refuse if either is still running: deleting a live data directory corrupts it
still=""
pgrep -f "[k]afka.Kafka $KAFKA_DATA" >/dev/null && still="$still kafka($KAFKA_DATA)"
pgrep -f "bin/[p]ostgres -D $PG_DATA" >/dev/null && still="$still postgres($PG_DATA)"
if [ -n "$still" ]; then
  echo "still running after the stop attempt:$still; nothing deleted. Stop it, then run make demo-reset again." >&2
  exit 1
fi

# 4. delete, saying what went
removed=0
for path in "$KAFKA_DATA" "$PG_DATA" "$LOGS/scorecard-snapshot.json"; do
  if [ -e "$path" ]; then
    size=$(du -sh "$path" 2>/dev/null | cut -f1)
    rm -rf -- "$path"
    echo "removed $path ($size)"
    removed=$((removed + 1))
  else
    echo "not there: $path"
  fi
done
echo "demo-reset: $removed path(s) removed; kept the Elasticsearch data and $PG_PWFILE. make demo starts from a clean database and empty topics."
