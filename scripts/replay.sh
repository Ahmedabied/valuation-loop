#!/usr/bin/env bash
# make replay: rebuild scorecard_fact from the Kafka topics and check it is identical (Claim 3).
# Starts the app once in replay mode (ScorecardReplay): snapshot the facts, stop the scorecard consumer, truncate the
# table, reset the consumer group to the earliest offsets, consume to the end, compare with the snapshot, exit 0 or 1.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh

if pid=$(app_pid); then
  echo "the demo app is still running (pid $pid); its scorecard consumer would fight the offset reset. Stop it first." >&2; exit 1
fi
if port_in_use 8080 || port_in_use 8081; then
  echo "port 8080 or 8081 is in use; replay starts the app and needs both free. Stop whatever holds them." >&2; exit 1
fi
wait_for_ram || exit 1

snapshot=$LOGS/scorecard-snapshot.json
rm -f "$snapshot"   # each replay compares against the facts as they are right now, not an older run's
mkdir -p "$LOGS"
PG_PASSWORD=$(cat "$PG_PWFILE") "$JAVA_HOME/bin/java" $APP_JAVA_OPTS -jar "$APP_JAR" --spring.profiles.active=local,demo \
    --scorecard.replay=true --valuationloop.scorecard.listener.auto-startup=false \
    --scorecard.replay.snapshot="$snapshot" --scorecard.replay.exit=true > "$LOGS/replay.log" 2>&1
status=$?
# the log is JSON (local profile); print the replay's own messages as plain lines
grep -oE '"message":"replay: [^"]*' "$LOGS/replay.log" | cut -d'"' -f4
[ $status -eq 0 ] && echo "make replay: exit 0" || { echo "make replay: exit $status (see $LOGS/replay.log)" >&2; }
exit $status
