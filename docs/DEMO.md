# make demo and make replay

`make demo` starts Postgres, Kafka and Elasticsearch (if they are not up), runs `make load` if `comparables-v1` is
missing, builds the jar, starts the app under the
`local,demo` profiles (logs: `~/tools/logs/app.log`, JSON), runs every scenario, prints what follows, and stops the app
it started. The services stay up; `make services-down` stops them. It can be run again straight away.

## What it prints, in order
1. **`POST /simulator/scenarios/all`**: one block per scenario: request id, its `traceId`, one line per lead
   (adapter, delivery attempts, state, delivery time, reply range, late, evidence attached) and the checks it made.
   | Scenario | What happens | Claim (ARCHITECTURE §0) |
   |---|---|---|
   | `ok` | 3 agents; the flaky CRM lands on attempt 3 via `-retry-0/-retry-1`. One agent builds a pack from live ES, removes a comparable, replies at its comparables' range and attaches it; the seller views all, asks that agent for a visit and selects it; the visit is booked; the CRM webhook (leadRef) reports the instruction; the scorecard fact shows `instructed=true` | 1, 2, 3 |
   | `slow` | same loop with the 2 s CRM; each lead's `lead.deliver.v1` partition and delivery time. Six partitions, three listener threads, keyed by leadId: the ok leads are delivered in a few hundred ms while the slow one takes 2 s (two leads hashed onto one thread would still queue; the line shows the partitions) | 1 |
   | `flaky-crm` | the flaky CRM never accepts: 3 attempts, `lead.deliver.v1-dlt`, `DELIVERY_FAILED`, `dlt_records_total` +1; the other lead is delivered, replies, the request goes on; the seller page never names the failed lead | 1 |
   | `late-reply` | a lead passes its reply window (the deadline service judged at `reply_due_at + 1 s`), `EXPIRED`; the late reply is accepted, `late=true` in the agent's own view, no late flag on the seller page | 3 |
2. **`POST /simulator/outbox-gap`**: relay paused, request committed: its rows sit unsent (the state a crash between
   commit and send leaves); resumed: the same eventIds reach the topics, each once. (The real kill test is `OutboxRelayTest`.)
3. **`GET /valuation-requests/{id}/comparison`** for the `ok` request: replies in reply order, the disclaimer, the pack.
4. **`GET /agents/{selected}/scorecard`**: that agent's own numbers next to a labelled fixture benchmark.
5. **`GET /scorecard/product-query`** (internal, demo profile): share of engagement and instructions won by the
   highest-range reply, split by evidence attached. Numbers grow with each run: the demo data accumulates. The
   scenarios are scripted (the evidenced agent replies at its comparables' range and is always the one selected), so
   these numbers show the query works end to end, not that evidence wins sellers.
6. **Metrics** from `127.0.0.1:8081/actuator/prometheus`:
   `outbox_unsent` (rows committed, not yet on Kafka; should return to 0), `dlt_records_total` (dead-lettered
   deliveries since start), `reply_minutes_count/_sum/_max` (delivery -> reply; near 0 in a simulation).
7. **One trace**: the first log line per logger carrying the `ok` scenario's traceId: the API thread, the outbox
   relay, the delivery consumer and the scorecard consumer share it, because the envelope carries `traceparent`.
   `grep <traceId> ~/tools/logs/app.log` shows the whole story.

## make replay
Run after a demo (the app must be stopped; the script refuses if 8080/8081 are taken). It starts the app in replay
mode: snapshot `scorecard_fact`, stop the scorecard consumer, truncate, reset group `scorecard` to the earliest
offsets, consume to the end, compare. Expected: `replay: N facts rebuilt`, `replay: IDENTICAL ...`, exit 0.

## Split run
The same jar as two processes (`application-api.yml`, `application-worker.yml`):

| Process | Profile | Ports | Runs |
|---|---|---|---|
| api | `local,demo,api` | 8080 / actuator 8081 | HTTP API, request/reply/evidence/outcome flows, scorecard consumer, simulator |
| worker | `local,demo,worker` | 8090 / actuator 8091 | `lead.deliver.v1` listener with its retry topics and DLT handler, deadline scanner, outbox relay |

The api still writes its outbox rows in its own transactions; the worker's relay (the only one running) sends them
from the shared Postgres. The DLT handler, and the request closure when every lead failed, run in the worker and
write through the same database and outbox. In the api the delivery listener bean exists but its containers never start.

`make run-api`, `make run-worker`, `make run-split` start them in the background (pidfiles and logs
`~/tools/logs/{api,worker}.{pid,log}`, api first so Flyway runs once at a time); `scripts/split.sh stop` stops them.
`make demo-split` starts the services and both processes, runs `POST /simulator/scenarios/all` against the api,
prints the report, how many delivery / DLT / scorecard log lines each process wrote, `dlt_records_total` and
`outbox_unsent` from the worker's 8091 and `reply_minutes` from the api's 8081, then stops both processes (the
services stay up). The simulator still calls the services in-process, but every DeliverLead now crosses from one JVM
to the other through Kafka: before the first scenario it waits for consumer group `delivery` to hold every
`lead.deliver.v1` partition (asked of the broker, not of local containers), then each scenario waits for its leads to
reach DELIVERED. The flaky-crm scenario sets the worker's flaky adapter and reads its DLT count through the worker's
demo-only `/internal/demo/delivery/*` routes. `POST /simulator/outbox-gap` is a one-process demo (it pauses the relay
of the process it runs in) and is not part of the split run.

## When something is off
`make status` (by port). The app log is `~/tools/logs/app.log`, the replay's `~/tools/logs/replay.log`. A scenario
that waits more than 30 s answers 500 with what it was waiting for. `make load` rebuilds the ES indices (about 25 s).

Migrations: the app runs Flyway on start, in version order. A database made by an earlier build (V1-V3 in
`~/tools/pgdata`) upgrades in place: the first start logs `Current version of schema "public": 3`, then migrates to 4
and 5 (checked 2026-10-17). `out-of-order` stays `false`, so if a database ever had V5 without V4, Flyway refuses to
start rather than apply V4 late; then either run `flyway repair` after checking the history table by hand, or rebuild
the demo database (`make pg-down`, move `~/tools/pgdata` aside, `make pg-up`; the demo data is all simulated).

## Side-by-side page
`make demo` stops the app at the end, so start it yourself: `make services-up`, then `make run` (profiles `local,demo`).
In a second terminal, run the ok scenario (it needs the comparables index, `make load`) and keep its request id:
`ID=$(curl -s -X POST 127.0.0.1:8080/simulator/scenarios/ok | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["requestId"])')`
then `make page ID=$ID` prints the URL to open. The ok scenario already chose an agent, so the page shows the three
replies with "Choose this agent" disabled; the other buttons still record engagement. `src/main/resources/static/comparison.html`.
