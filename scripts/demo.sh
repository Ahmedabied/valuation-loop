#!/usr/bin/env bash
# make demo: start the app (local,demo) against the running services, run every scenario, print what a person needs
# to see, stop the app. The services stay up (make services-down stops them). Safe to run twice in a row.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh

started_here=false
cleanup() { if $started_here; then stop_app; fi; }
trap cleanup EXIT

# 1. Elasticsearch must hold the comparables index; build it once if it is missing (make load, about 25 s)
if [ "$(curl -s -o /dev/null -w '%{http_code}' 127.0.0.1:9200/comparables-v1)" != 200 ]; then
  echo "comparables-v1 missing: running make load first"; make --no-print-directory load
fi
echo "elasticsearch: comparables-v1 holds $(curl -s 127.0.0.1:9200/comparables-v1/_count | grep -oE '"count":[0-9]+' | cut -d: -f2) docs"

# 2. the app: reuse one this script started earlier and left running, refuse a port someone else holds
if pid=$(app_pid); then
  echo "app already running (pid $pid, from $PIDFILE): using it"
elif port_in_use 8080 || port_in_use 8081; then
  echo "port 8080 or 8081 is in use by a process this demo did not start; stop it first" >&2; exit 1
else
  wait_for_ram
  start_app
  started_here=true
  echo "app starting (pid $(cat "$PIDFILE"), log $LOGS/app.log)"
  wait_for_health
fi

# 3. the scenarios (the report is text rendered by the app's SimulatorReportPrinter)
echo; echo "######## POST /simulator/scenarios/all"
curl -sf -X POST "$API/simulator/scenarios/all?format=text" | tee "$LOGS/demo-report.txt"
request_id=$(grep -m1 -oE '^request vr_[A-Za-z0-9_-]+' "$LOGS/demo-report.txt" | cut -d' ' -f2)   # the ok scenario runs first
selected_agent=$(grep -m1 -oE '^seller selected ag_[a-z_0-9]+' "$LOGS/demo-report.txt" | cut -d' ' -f3)

echo "######## POST /simulator/outbox-gap"
curl -sf -X POST "$API/simulator/outbox-gap?format=text"

# 4. what the seller saw for the ok scenario (condensed; the full JSON is in demo-comparison.json)
echo; echo "######## GET /valuation-requests/$request_id/comparison"
curl -sf "$API/valuation-requests/$request_id/comparison" | tee "$LOGS/demo-comparison.json" | python3 -I -c '
import json, sys
v = json.load(sys.stdin)
print("state", v["state"], "|", v["disclaimer"])
for r in v["replies"]:
    e = r["evidence"]
    cr = e and e["comparablesRange"]
    ev = "no evidence" if e is None else "evidence: %d comparables, comparablesRange %s / %s / %s, data %s" % (
        len(e["comparables"]), format(cr["low"], ","), format(cr["mid"], ","), format(cr["high"], ","), e["dataVintage"])
    rg = r["range"]
    print("  %-28s %9s - %-9s %s" % (r["agentName"], format(rg["low"], ","), format(rg["high"], ","), ev))
    print("  %-28s \"%s\"" % ("", r["message"]))'

echo; echo "######## GET /agents/$selected_agent/scorecard (the agent's own numbers only)"
curl -sf "$API/agents/$selected_agent/scorecard" | python3 -m json.tool

echo; echo "######## GET /scorecard/product-query (internal: do evidenced replies win the seller?)"
curl -sf "$API/scorecard/product-query" | python3 -m json.tool

echo; echo "######## Prometheus (127.0.0.1:8081/actuator/prometheus)"
curl -sf "$ACTUATOR/actuator/prometheus" | grep -E '^(outbox_unsent|dlt_records_total|reply_minutes_(count|sum|max)) '

trace=$(grep -m1 -oE 'traceId [0-9a-f]{32}' "$LOGS/demo-report.txt" | cut -d' ' -f2 || true)
if [ -n "$trace" ]; then
  echo; echo "######## one trace, many threads: the first app.log line per logger with the ok scenario's traceId $trace"
  grep "$trace" "$LOGS/app.log" | python3 -I -c '
import json, sys
seen = set()
for line in sys.stdin:
    d = json.loads(line)                      # ECS JSON (application-local.yml)
    thread = d["process"]["thread"]["name"]
    logger = d["log"]["logger"].rsplit(".", 1)[-1]
    if logger not in seen:
        seen.add(logger)
        print("  %-22s %-22s %s" % (thread[:22], logger, d["message"][:84]))'
fi
echo; echo "make demo: done (report in $LOGS/demo-report.txt)"
