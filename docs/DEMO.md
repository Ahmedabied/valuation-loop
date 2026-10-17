# make demo and make replay

`make demo` starts Postgres, Kafka and Elasticsearch (if they are not up), builds the jar, starts the app under the
`local,demo` profiles (logs: `~/tools/logs/app.log`, JSON), runs every scenario, prints what follows, and stops the app
it started. The services stay up; `make services-down` stops them. It can be run again straight away.

## What it prints, in order
1. **`POST /simulator/scenarios/all`**: one block per scenario: request id, its `traceId`, one line per lead
   (adapter, delivery attempts, state, delivery time, reply range, late, evidence attached) and the checks it made.
   | Scenario | What happens | Claim (ARCHITECTURE §0) |
   |---|---|---|
   | `ok` | 3 agents; the flaky CRM lands on attempt 3 via `-retry-0/-retry-1`. One agent builds a pack from live ES, removes a comparable, replies at its comparables' range and attaches it; the seller views all, asks that agent for a visit and selects it; the visit is booked; the CRM webhook (leadRef) reports the instruction; the scorecard fact shows `instructed=true` | 1, 2, 3 |
   | `slow` | same loop with the 2 s CRM; delivery time per lead. One partition: a lead queued behind the slow one waits too | 1 |
   | `flaky-crm` | the flaky CRM never accepts: 3 attempts, `lead.deliver.v1-dlt`, `DELIVERY_FAILED`, `dlt_records_total` +1; the other lead is delivered, replies, the request goes on; the seller page never names the failed lead | 1 |
   | `late-reply` | a lead passes its reply window (the deadline service judged at `reply_due_at + 1 s`), `EXPIRED`; the late reply is accepted, `late=true` in the agent's own view, no late flag on the seller page | 3 |
2. **`POST /simulator/outbox-gap`**: relay paused, request committed: its rows sit unsent (the state a crash between
   commit and send leaves); resumed: the same eventIds reach the topics, each once. (The real kill test is `OutboxRelayTest`.)
3. **`GET /valuation-requests/{id}/comparison`** for the `ok` request: replies in reply order, the disclaimer, the pack.
4. **`GET /agents/{selected}/scorecard`**: that agent's own numbers next to a labelled fixture benchmark.
5. **`GET /scorecard/product-query`** (internal, demo profile): share of engagement and instructions won by the
   highest-range reply, split by evidence attached. Numbers grow with each run: the demo data accumulates.
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

## When something is off
`make status` (by port). The app log is `~/tools/logs/app.log`, the replay's `~/tools/logs/replay.log`. A scenario
that waits more than 30 s answers 500 with what it was waiting for. `make load` rebuilds the ES indices (about 25 s).
