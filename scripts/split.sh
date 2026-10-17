#!/usr/bin/env bash
# The same jar as two processes (docs/DEMO.md "Split run"):
#   api    (profile api)    HTTP 8080, actuator 8081: requests, replies, evidence, outcomes, scorecard consumer, simulator
#   worker (profile worker) HTTP 8090, actuator 8091: DeliverLead listener + retry/DLT, deadline scanner, outbox relay
# Usage: scripts/split.sh api | worker | both | stop | demo
#   api / worker / both   start in the background (pidfiles $LOGS/api.pid, $LOGS/worker.pid) and wait for health
#   stop                  stop whichever of the two this script started (by pidfile; never anything else)
#   demo                  start both, run every scenario against the api, print the report and the worker's metrics,
#                         stop what it started
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh

PROFILES_BASE=local,demo   # demo: the simulator (api) and its internal delivery routes (worker)

port_of()     { case $1 in api) echo 8080 ;; worker) echo 8090 ;; esac; }
mgmt_port_of() { case $1 in api) echo 8081 ;; worker) echo 8091 ;; esac; }
pidfile_of()  { echo "$LOGS/$1.pid"; }
pid_of()      { local f; f=$(pidfile_of "$1"); [ -f "$f" ] && kill -0 "$(cat "$f")" 2>/dev/null && cat "$f"; }

start_one() {   # $1 = api | worker; returns 0 if it started it, 1 if it was already running (started earlier by this script)
  local name=$1 port mgmt
  port=$(port_of "$name"); mgmt=$(mgmt_port_of "$name")
  if pid=$(pid_of "$name"); then echo "$name already running (pid $pid): using it"; return 1; fi
  if port_in_use "$port" || port_in_use "$mgmt"; then
    echo "port $port or $mgmt is in use by a process this script did not start (make demo's app?); stop it first" >&2; exit 2
  fi
  wait_for_ram || exit 1
  mkdir -p "$LOGS"
  PG_PASSWORD=$(cat "$PG_PWFILE") nohup "$JAVA_HOME/bin/java" $APP_JAVA_OPTS -jar "$APP_JAR" \
      --spring.profiles.active=$PROFILES_BASE,"$name" > "$LOGS/$name.log" 2>&1 &
  echo $! > "$(pidfile_of "$name")"
  echo "$name starting (pid $!, log $LOGS/$name.log)"
  for i in $(seq 1 90); do
    if curl -s "127.0.0.1:$mgmt/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then echo "$name healthy after ${i} s"; return 0; fi
    if ! pid_of "$name" >/dev/null; then echo "$name exited during startup; last log lines:" >&2; tail -20 "$LOGS/$name.log" >&2; exit 1; fi
    sleep 1
  done
  echo "$name not healthy after 90 s; see $LOGS/$name.log" >&2; exit 1
}

stop_one() {
  local name=$1 pid
  pid=$(pid_of "$name") || { rm -f "$(pidfile_of "$name")"; return 0; }
  kill "$pid"
  for i in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  kill -0 "$pid" 2>/dev/null && { echo "$name $pid did not stop in 30 s; sending SIGKILL"; kill -9 "$pid"; }
  rm -f "$(pidfile_of "$name")"
  echo "$name stopped (pid $pid)"
}

# api first, then worker: both run Flyway on the shared database at startup; one after the other keeps that simple
start_both() { start_one api || true; start_one worker || true; }

started=()   # what demo started, stopped again on exit (global: the EXIT trap runs after demo's scope is gone)
demo() {
  cleanup() { for n in "${started[@]}"; do stop_one "$n"; done; }
  trap cleanup EXIT

  if [ "$(curl -s -o /dev/null -w '%{http_code}' 127.0.0.1:9200/comparables-v1)" != 200 ]; then
    echo "comparables-v1 missing: running make load first"; make --no-print-directory load
  fi
  if start_one api; then started+=(api); fi
  if start_one worker; then started+=(worker); fi

  echo; echo "######## POST 127.0.0.1:8080/simulator/scenarios/all (api process; delivery runs in the worker)"
  curl -sf -X POST "127.0.0.1:8080/simulator/scenarios/all?format=text" | tee "$LOGS/demo-split-report.txt"

  echo "######## who did what (log lines per process)"
  printf "  %-40s api %4s   worker %4s\n" "'delivered via' (DeliverLead consumer)" \
      "$(grep -c "delivered via" "$LOGS/api.log" || true)" "$(grep -c "delivered via" "$LOGS/worker.log" || true)"
  printf "  %-40s api %4s   worker %4s\n" "'marked DELIVERY_FAILED' (DLT handler)" \
      "$(grep -c "marked DELIVERY_FAILED" "$LOGS/api.log" || true)" "$(grep -c "marked DELIVERY_FAILED" "$LOGS/worker.log" || true)"
  printf "  %-40s api %4s   worker %4s\n" "'scorecard applied' (scorecard)" \
      "$(grep -c "scorecard applied" "$LOGS/api.log" || true)" "$(grep -c "scorecard applied" "$LOGS/worker.log" || true)"

  echo; echo "######## worker metrics (127.0.0.1:8091/actuator/prometheus)"
  curl -sf "127.0.0.1:8091/actuator/prometheus" | grep -E '^(outbox_unsent|dlt_records_total) '
  echo "######## api metrics (127.0.0.1:8081/actuator/prometheus)"
  curl -sf "127.0.0.1:8081/actuator/prometheus" | grep -E '^(reply_minutes_(count|sum|max)|scorecard_skipped_total) '
  echo; echo "make demo-split: done (report in $LOGS/demo-split-report.txt, logs $LOGS/api.log and $LOGS/worker.log)"
}

case "${1:-}" in
  api)    start_one api || true ;;
  worker) start_one worker || true ;;
  both)   start_both ;;
  stop)   stop_one worker; stop_one api ;;
  demo)   demo ;;
  *)      echo "usage: scripts/split.sh api | worker | both | stop | demo" >&2; exit 2 ;;
esac
