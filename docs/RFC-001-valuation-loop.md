# RFC-001: Valuation Loop

- **Status:** accepted for v1, open for review
- **Owner:** Ahmed
- **Date:** 2026-10-17
- **Repo:** this one. Event contract: [EVENTS.md](EVENTS.md). Decisions: [adr/](adr/).

This RFC describes a backend for the moment a homeowner on Rightmove asks several agents for a valuation and
chooses one. It proves mechanics: a lead reaches each agent through an unreliable integration, the reply can carry
evidence built from real public data, and what the homeowner and the agent do next joins back to the lead in a
replayable log. It does not prove business impact. Property data is real (Land Registry Price Paid and EPC);
agents, CRMs, sellers and outcomes are simulated. What it hands the product team is an instrument: the facts and the
query that would answer "do evidenced answers win the seller?" on real traffic.

## 1. The three claims

**Claim 1. A lead reaches each selected agent reliably through an unreliable adapter, and reply time and "evidence
attached" are facts in an idempotent log.**
The request, its leads and one `DeliverLead` command per lead are written in one transaction with their events in
an outbox table; a relay sends committed rows to Kafka. A non-transactional listener on `lead.deliver.v1` calls the
agent's CRM adapter; a failure moves the record to `lead.deliver.v1-retry-0`, then `-retry-1`, then `-dlt`, where the
lead becomes `DELIVERY_FAILED`. A duplicate command finds the lead already `DELIVERED` and does nothing.
Tests: `delivery/DeliveryRetryAndDltTest.flakyLeadReachesTheDltAfterThreeAttemptsAndTheOthersAreDelivered`,
`delivery/DuplicateDeliveryTest.theSameDeliverLeadTwiceDeliversOnce`,
`delivery/OutboxLatencyTest.firstEventIsOnTheTopicWithinOneSecondOfTheApiCall`,
`outbox/OutboxRelayTest.eventCommitsWithTheTransactionAndIsSentExactlyOnceByTheRelay`.

**Claim 2. Every reply can carry an evidence pack built to one standard from real public data, chosen and curated
by the agent, machine-readable, with provenance.**
The agent asks for a pack; one Elasticsearch `function_score` query ranks real sales near the home by distance,
recency and floor area. A comparable with no floor area gets a weight of 0.5 in the same query, because a decay on a
missing field would otherwise score it as a perfect match. The agent removes comparables it does not want (privately)
and attaches the pack; the pack carries its range, data vintage, method and the exact query parameters.
Tests: `evidence/ComparablesQueryTest.missingFloorAreaGetsHalfWeightInTheSameQuery` (no Elasticsearch needed),
`evidence/ComparablesRankingEsTest.floorAreaMatchRanksAboveMissingFloorArea` and
`evidence/ComparablesRankingEsTest.packFromLiveEsHasProvenanceAndAttachEmitsASchemaValidEvent` (live Elasticsearch,
`./gradlew test -Pes=true`).

**Claim 3. The seller's choice, the visit and the instruction join back to the lead even when late and out of order,
and one replayable query answers "do evidenced answers win the seller, and at what price gap?".**
Every lead-level event is keyed by `requestId` and carries `leadId`. The scorecard consumer upserts one
`scorecard_fact` row per `lead_id`, and every rule is order-independent: flags only turn on, the earliest reply wins,
and range ranks are recomputed from the set of ranges. So the table is a pure function of the event log: truncate it,
reset the consumer group to the earliest offset, and it rebuilds identical.
Tests: `scorecard/ScorecardReplayTest.outOfOrderLateAndDuplicateEventsProjectDeterministicallyAndReplayRebuildsIdenticalFacts`,
`scorecard/ProductQueryTest.sharesCapturedByTheHighestRangeReplySplitByEvidence`.

**One real number.** For 400 random flat and house sales (seed 42) in SW11, SW4, SW12, SW18, SW2, SW9, SE24 and SE11,
each used as a pseudo-request against the same pack query with itself excluded, the median width of the comparables
range ((upper quartile - lower quartile) / median of the top 10 comparable prices) is **0.280** (IQR 0.191 to 0.373;
flats n=283, median 0.299; houses n=117, median 0.219). When the comparables themselves disagree by 28% of value, no
single number earns a verdict. That is the technical reason the product shows evidence and never a verdict on an
agent. `make stats` reproduces it from the loaded index.

## 2. The problem

Agent Select's job is that the moment a homeowner chooses an agent on Rightmove ends with a confident homeowner
instructing one of the agents Rightmove connected them to, and with that agent able to see it happened. Agents buy the
valuation-lead products because of that moment.

Two things are missing at that moment today. The homeowner gets prices from several agents and cannot compare them on
evidence. And the platform does not own the join from lead to visit to instruction, so neither side can see what a
good answer looks like. The design gives the homeowner the evidence each agent chose to attach, gives each agent a
private view of its own numbers, and records the facts a product team needs, without Rightmove judging any agent.

## 3. Where it sits in the real flow

```
Seller                      Rightmove (this repo in [ ])                         Agent
instant estimate (exists)
Find Agent (exists)
select agents / OAV form -> [ValuationRequested]
                            [match = eligibility lookup only: slot holders
                             for the outcode, or the agents the seller picked]
                            [DeliverLead -> adapter, retries, DLT] ----------> CRM (simulated)
                                                             <--------------- reply: range + message [+ evidence pack]
                            [contact released only after the reply]
compare replies side by side <- [comparison view: replies in reply order,
   with the evidence each agent attached, disclaimer]
   [SellerEngaged / SellerSelected]
                                                             <--------------- [VisitBooked]
                                                             <--------------- [InstructionRecorded, webhook with leadRef]
listing / sale / completion (v2: Land Registry late match)
                            [private scorecard per agent] -----------------> agent sees own numbers only
```

## 4. Shape: one app

One Spring Boot application, modules as packages: `request`, `lead`, `agent`, `outbox`, `delivery`, `response`,
`evidence`, `comparison`, `outcome`, `scorecard`, `dataloader`, `api`. Postgres is the system of record, Kafka carries
the asynchronous seams, Elasticsearch serves comparables. Consumers exist only where asynchrony is real: the outbound
CRM call and the replayable scorecard. The seller view reads Postgres directly.

Why not several services: a design with four deployables had three of them producing events without an outbox, would
not fit a 14 GB laptop next to Elasticsearch and Kafka, and cost about half again the effort. The split of `delivery`
into a second deployable is a v2 task and is small because the Kafka seam already exists. Module boundaries are
package conventions in v1, not enforced by a tool. See [ADR 0001](adr/0001-modular-monolith.md).

## 5. State machines

`ValuationRequest` (`request/RequestTransitions`):
```
RECEIVED -matched-> MATCHED -dispatched-> AWAITING_REPLIES -first reply-> REPLIES_IN -seller selects-> CLOSED
                                               |
                                               +-all leads terminal, none replied-> CLOSED
```

`AgentLead` (`lead/LeadTransitions`, one per agent per request, a pure `switch` over a sealed `LeadEvent`):
```
CREATED -deliver-> DELIVERING(n) -ok-> DELIVERED -reply-> REPLIED -evidence-> EVIDENCED -visit-> VISIT_BOOKED
                       |                   |                 |                                    ^
                       |                   |                 +---------------visit----------------+
                       |                   +-deadline-> EXPIRED -late reply-> REPLIED (late=true)
                       |-fail, n<3-> DELIVERING(n+1)
                       +-fail, n=3-> DELIVERY_FAILED
```
An instruction is a fact on the lead (`instructed_at`), not a state: it is recorded in any state, because a seller may
phone the agent directly. Contact details unlock only once the lead has replied.

**Closure rule (decided 2026-10-17).** A request closes with reason `ALL_LEADS_TERMINAL` only when every lead is
terminal and none replied. Two places check it: the deadline scanner after it expires a lead, and the DLT handler
after a lead becomes `DELIVERY_FAILED` (so a request whose every CRM failed closes too;
`delivery/DeliveryRetryAndDltTest.aRequestWhoseOnlyLeadFailsDeliveryCloses`). If any lead replied, the request stays `REPLIES_IN` until the seller selects
(reason `SELLER_SELECTED`). Code: `request/RequestCloser`; test:
`delivery/DeadlineScannerTest.aRequestWithAReplyStaysOpenWhenTheOtherLeadsExpire`. Confirmed by the owner on
2026-10-17 (§19, question 1).

## 6. Events and topics

One JSON envelope for every event: `eventId` (UUIDv7, the idempotency key), `eventType`, `schemaVersion`,
`occurredAt`, `requestId`, `leadId`, `agentId`, `traceparent`, `payload`. Every event type has a JSON Schema in
`src/main/resources/events/`, and tests validate emitted events against it. Producer: `acks=all`,
`enable.idempotence=true`; values are JSON strings.

| Topic | Key | Events | Consumer |
|---|---|---|---|
| `valuation.requested.v1` | requestId | ValuationRequested, AgentsMatched, RequestClosed | none in v1 |
| `lead.deliver.v1`, `lead.deliver.v1-retry-0`, `lead.deliver.v1-retry-1`, `lead.deliver.v1-dlt` | leadId | DeliverLead (a command) | delivery |
| `lead.events.v1` | requestId | LeadDelivered, LeadDeliveryFailed, LeadReplied, EvidenceAttached, LeadExpired, SellerEngaged, SellerSelected, VisitBooked | scorecard |
| `outcome.recorded.v1` | requestId | InstructionRecorded | scorecard |

**Correction to the architecture note.** ARCHITECTURE.md §4 names the retry topics `-retry-0..2`. That is an
off-by-one: 3 attempts is one try on the main topic plus 2 retries, so the topics are `-retry-0` and `-retry-1`.
`delivery/DeliveryRetryAndDltTest.springKafkaCreatedTheRetryAndDltTopicsWithTheseNames` pins the real names.

**Keys.** Everything about one request is keyed by `requestId`, so it lands on one partition and the scorecard sees it
in order. `DeliverLead` is keyed by `leadId` so that, with several partitions and listener threads, deliveries for
different agents spread out. A failed delivery never holds up the leads behind it: it waits for its retry on a retry
topic. A slow one does, in v1: the adapter call blocks the listener thread, and the local topic has one partition and
one consumer thread, so the demo's `slow` scenario shows an `ok` lead queued behind a 2 s CRM. Production needs more
partitions, listener concurrency and a per-CRM timeout (a bulkhead), not a different key.

## 7. The outbox

One breath: *the state change and the event commit together; the relay sends at least once; the consumer is
idempotent.*

- `OutboxWriter.write` inserts the envelope into `outbox` and refuses to run outside a transaction, so an event can
  never be written without the state change it describes.
- `OutboxRelay` ticks every 300 ms. In one transaction it selects unsent rows `FOR UPDATE SKIP LOCKED`, sends each,
  waits for the broker's acknowledgement and sets `sent_at`. A send failure stops the batch so later rows for the same
  key do not overtake it.
- Crash after commit, before send: the row is unsent and goes on the next tick or restart.
- Crash after send, before `sent_at` commits: the row is still unsent and is sent again. Consumers absorb the repeat
  (delivery skips a lead already delivered; the scorecard upserts by `lead_id`). End to end that is effectively once,
  not exactly once.
- `SKIP LOCKED` lets several app instances run the relay without sending the same row twice. It does not keep
  per-key order across instances (one can send a later row for a key while another still holds an earlier one); v1
  runs one instance, and the scorecard's rules do not depend on order anyway.

`OutboxRelayTest` stands in the gap: the relay scheduler is off, the test commits, checks the topic is empty, runs the
relay as a restart would, and sees exactly one record; a second relay run sends nothing.
`OutboxLatencyTest` guards the other trap: with the scheduler on, the first event is on the topic within 1 s of the API
call. Why this is hand-rolled rather than Spring Modulith's event publication: [ADR 0002](adr/0002-hand-rolled-outbox.md).

## 8. Delivery, retries, dead letter, optimistic locks

`LeadDeliveryListener` is a `@KafkaListener` with `@RetryableTopic(attempts = "3")`, backoff 500 ms then 1000 ms.
Retries are non-blocking: a failed record is re-published to the next retry topic and the partition moves on.
Malformed commands and unknown adapters skip the retries and go straight to the DLT.

The listener is not `@Transactional`. It calls `LeadDeliveryService`, which runs three short transactions through
Spring's proxy (`DeliveryTransactions`): commit "attempt n started", call the adapter with no transaction open, commit
"delivered" with `LeadDelivered`. The container commits the offset only after the listener returns (`ack-mode: record`),
so a crash in between redelivers the record and the state check makes it a no-op. Holding a transaction across a
third-party call would pin a connection and roll back the attempt counter on failure.

The DLT handler marks the lead `DELIVERY_FAILED` and writes `LeadDeliveryFailed` with the adapter, attempt count and
cause. That event is agent-private and never reaches the seller page. Simulated adapters are `ok`, `slow` and `flaky`
(fails the first N calls per lead); each call carries `leadId` as its idempotency key.

`agent_lead` and `valuation_request` carry `@Version`. When the delivery worker, the deadline scanner and the reply API
touch the same row, the loser gets an optimistic-lock exception. `OptimisticLockRetry` re-runs that step in process
(5 tries, short backoff), so a lock conflict never travels to the retry topics or the DLT. If it still fails at an
HTTP endpoint the caller gets a 409.

A `@Scheduled` deadline scanner expires delivered leads past `reply_due_at` (24 h after delivery in v1) with
`LeadExpired`, one small transaction per lead. A late reply is still accepted and marked `late`.

## 9. Evidence pack and the one query

`POST /agents/{id}/leads/{leadId}/evidence` builds a pack from Elasticsearch with one `function_score` query
(`evidence/ComparablesQuery`):

- filters: same property type, within 1,500 m of the request's postcode centroid, sold in the last 24 months;
- scores, multiplied: gauss on distance (scale 400 m), gauss on sale date (scale 180 days), gauss on floor area
  (scale 15 m², offset 5 m²) when the request has a floor area;
- and a weight-only function, `filter: bool.must_not.exists(floorAreaM2)`, `weight: 0.5`.

Why the weight: Elasticsearch returns 1 for a decay function on a field the document does not have. Without the
weight, a sale with no EPC match would rank as a perfect floor-area match and outrank an equal sale whose area really
matches. With it, the unknown ranks below the match and above a far-off area. The loader therefore leaves
`floorAreaM2` out when there is no EPC match and never writes a sentinel. Postgres could do the distance part for a
demo; Elasticsearch is kept for the decay-function ranking and because it is in the team's stack, which is partly
stack fidelity. See [ADR 0004](adr/0004-elasticsearch-for-comparables.md).

A pack holds at most 10 comparables (`ppdTransactionId`, price, date, distance, floor area if known, energy rating,
property type, built form), a `comparablesRange` {low, mid, high}, the data vintage (`ppd-2024-2026+epc-2026-10`), the
method and the provenance (attributions, index and the query parameters in words). The method is "lower quartile /
median / upper quartile of attached comparables". With fewer than 3 comparables the range is null, the pack says why,
and it cannot be attached. The field is called `comparablesRange`: the range implied by the comparables this agent
attached. It is not a valuation and not a judgement. The agent removes comparables with a DELETE; exclusions stay in
the agent's private record. Attaching moves the lead `REPLIED -> EVIDENCED` and writes `EvidenceAttached` in one
transaction.

## 10. Seller comparison view

`GET /valuation-requests/{id}/comparison` is one SQL join over `agent_lead` and `evidence_pack`
(`comparison/ComparisonQuery`). It lists only leads that replied, in reply order, each with the agent's own range,
message, reply time and the pack it attached, or `evidence: null`. A fixed disclaimer says each range is that agent's
own opinion, the evidence is what each agent chose to attach, and nothing on the page rates an agent.

It is a join and not a Kafka consumer because both tables live in this database: a join reads what committed a
millisecond ago, while a consumer-maintained copy would lag and could show a stale page right after a reply. Nothing
on the page is scored, ranked or reordered by behaviour, and leads that did not reply are not listed or counted.

`POST .../engagement` writes `SellerEngaged {VIEWED | CONTACTED | VISIT_REQUESTED}`. `POST .../selection` moves the
request to `CLOSED` and writes `SellerSelected` and `RequestClosed {SELLER_SELECTED}` in one commit; a second
selection is a 409.

## 11. Outcomes with leadRef

`POST /agents/{id}/leads/{leadId}/visit` moves the lead to `VISIT_BOOKED` with `VisitBooked`. `POST /outcomes/webhook`
is the simulated CRM push: it carries `leadRef`, the portal's lead id echoed back, as real CRM integrations do. So
attribution is a primary-key lookup, not an address match. The webhook is idempotent on the lead's own
`instructed_at`: a repeat returns 200 and writes nothing, because a CRM that retries should hear "got it", not an
error. The address-based Land Registry late match for completions is v2.

## 12. Scorecard and replay

`ScorecardListener` consumes `lead.events.v1` and `outcome.recorded.v1` (group `scorecard`, earliest offset).
`ScorecardProjector` upserts `scorecard_fact` by `lead_id` with: delivered and replied times, reply minutes,
within the reply window or not, evidence attached, the reply's range rank among that request's replies (highest,
middle or lowest by midpoint, null until there are two replies), seller engaged, seller selected, visit booked,
instructed. Because the upsert is by natural key, no dedupe table is needed. (`processed_event` exists in V1 for a
future consumer whose side effect is not an upsert; no v1 consumer uses it.)

`GET /agents/{id}/scorecard` returns that agent's own numbers next to a static value labelled "fixture benchmark".
It never returns another agent's id or numbers and never a position in a list.

`make replay` runs `ScorecardRebuilder`: stop the listener, truncate `scorecard_fact`, reset the group to the earliest
offsets with Kafka's AdminClient, start the listener, wait for lag 0, then compare with a snapshot and exit 0 if
identical. `ScorecardReplayTest` does the same in a test, after feeding 2 requests x 3 agents of events in a
deliberately bad order (outcome before reply, reply before delivery, a duplicate).

## 13. The product query

`src/main/resources/sql/product-query.sql`, served at `GET /scorecard/product-query` under the demo and test profiles:
over replies that have a range rank, split by whether the reply carried evidence, what share of seller engagement and
of instructions went to the highest-range reply.

The decision it informs: if evidenced replies lower the share captured by the highest number, the pack is worth
building for sellers; if they do not, the pack is an agent tool only and the roadmap changes. It is aggregated and
names no agent. On simulated data its output means nothing; on real traffic it is the instrument.

## 14. Data, licences, personal data

- Land Registry Price Paid Data 2024 to 2026, category A, Wandsworth and Lambeth: 2,291,778 rows read, 20,882 sales
  indexed. OGL v3.0 with the Land Registry attribution (README).
- OS Code-Point Open: 41,080 postcode centroids, converted from OSGB36 to WGS84 by hand from the OS guide constants
  (`dataloader/OsgbToWgs84`, tested in `OsgbToWgs84Test`). OS, Royal Mail and National Statistics attribution (README).
- EPC: 90.2% of sales matched to a certificate by a hashed normalised address key held only in loader memory. Only
  OGL-licensed derived fields are indexed (floor area, energy rating, built form, construction age, habitable rooms,
  property type refinement), with the register's OGL attribution in every pack. Address fields are never stored,
  indexed, logged or committed.
- **The EPC decision.** The EPC register limits use of its address fields to purposes related to the energy
  performance of buildings. This demo uses EPC addresses only in memory, to join floor area to sales, and is a
  non-commercial demonstration. A production deployment would use the operator's own address licences (Rightmove has
  them) or run with `loader.epc.enabled=false`, which the loader supports.
- The loader runs under the `loader` profile with Elasticsearch only (`make load`, about 23 s).

**Personal data.** The system holds a pseudonymous `sellerRef` and nothing else about the seller: no names, emails or
phone numbers exist in it, and the "contact" released after a reply is a simulated token. No photos. An agent sees only
its own leads and its own numbers; another agent's lead is a 404, not a 403, so its existence does not leak. v1 sets no
retention period. The real product would set one by its own policy; this is a known gap.

## 15. Security

- **Webhook.** v1 checks a shared secret in `X-Webhook-Token`, compared in constant time, before the body is read
  (`outcome/WebhookTokenCheck`); a wrong or missing token is 401. Production would give each CRM its own key and verify
  an HMAC signature over the body, which also binds the `leadRef` to the agent whose CRM signed it, so one agent's CRM
  cannot record another agent's instruction.
- **Agent identity.** v1 has none: the `agentId` in the path stands in for a logged-in agent and every query is scoped
  to it. Production puts it behind the platform's agent authentication.
- **Network.** The API binds to 127.0.0.1, Actuator to 127.0.0.1:8081, Elasticsearch and Kafka to loopback, Postgres to
  127.0.0.1 with a SCRAM password in a mode-600 file.
- **Internal endpoints.** `/scorecard/snapshot` and `/scorecard/product-query` exist only under the `demo` and `test`
  profiles (`@Profile`), so a production build does not have the routes at all.
- **Responses.** One JSON error shape, `{"error", "message"}`, from `api/ApiErrors`, with no stack traces; every
  response carries `X-Content-Type-Options: nosniff` (`api/NoSniffFilter`).

## 16. Observability

- **Traces.** Micrometer Tracing with the OpenTelemetry bridge gives every HTTP request a span. When an event is
  written, `observability/EventTracing` puts that span's W3C `traceparent` into the envelope; the outbox relay and
  both consumers open a child span from it. The trace travels inside the event, not in Kafka headers, because the
  relay sends on a scheduler thread after the request has finished. Sampling is 1.0 under the `local` profile.
- **Logs.** Under the `local` profile (`make demo`, `make replay`) the app logs ECS JSON, one object per line, with
  `traceId` and `spanId` from the MDC (`application-local.yml`); one `grep <traceId>` shows the API thread, the relay,
  the delivery consumer and the scorecard consumer for one request. Tests keep plain text logs.
- **Metrics** (`observability/LoopMetrics`, on `127.0.0.1:8081/actuator/prometheus`, no agent label on any of them):
  `outbox_unsent` (gauge: rows committed but not yet on Kafka, sampled every relay tick), `dlt_records_total`
  (counter: DeliverLead records that reached `lead.deliver.v1-dlt`), `reply_minutes` (summary: delivery to reply,
  exported as `reply_minutes_count`, `_sum`, `_max`).
- Actuator also serves `health`, `info` and `metrics` on that loopback port. Jaeger (a trace UI) is v2.

## 17. Versions

Java 21, Spring Boot 3.5.16, spring-kafka 3.3.16 (kafka-clients 3.9.2), Kafka 4.3.1 broker in KRaft mode, Postgres 18,
Elasticsearch 8.19.4 with `elasticsearch-java` 8.18.8, Gradle 8.14.3 Kotlin DSL, Flyway. Tests use H2 in PostgreSQL
mode with the same migrations and `@EmbeddedKafka(kraft = true)`, so they need no services. Spring Boot 3.5 left
open-source support on 2026-06-30; the bump to Boot 4.1 is v2 day 1. It touches the version lines, the Jackson
imports (Jackson 3 moves to `tools.jackson`), spring-kafka 4.x and the Elasticsearch client line.

## 18. What was deliberately left out

- Kafka consumers inside `request` to maintain the seller view. The view is a join.
- Address-based attribution of CRM outcomes, parked outcomes and a re-attribution pass. The webhook carries `leadRef`.
- The Land Registry late match. v2.
- Reply nudges, closing requests on a timer, withdrawn outcomes.
- A second query merged with the first for missing floor areas. One query with a weight does it.
- A staging table for sales before Elasticsearch, and an index per data vintage behind an alias.
- A lead history table. The outbox plus the lead's state is the history.
- Spring Data Elasticsearch. One Elasticsearch client, the official Java one.
- Operator endpoints to replay the DLT or resubmit events, and pausing consumers when a dependency is down.
- CI with Testcontainers in week one, and Jaeger.
- Spring Modulith's event publication ([ADR 0002](adr/0002-hand-rolled-outbox.md)).
- Any ordering or scoring of agents ([ADR 0003](adr/0003-no-ranking-of-agents.md)).

## 19. Open questions for the owner

1. **Closure rule.** Decided 2026-10-17 (§5): `ALL_LEADS_TERMINAL` only when every lead is terminal and none
   replied, checked by the deadline scanner and the DLT handler; with any reply the request waits in `REPLIES_IN` for
   the seller. Open: should a request with replies but no selection ever close on its own, and after how long?
2. **One attached pack per lead.** Enforced in `EvidenceService.attach` and by the lead's state table, because H2 cannot
   run the partial unique index (`unique (lead_id) where attached_at is not null`). Should Postgres also get the
   index, as a Postgres-only migration?
3. **Reply window.** v1 uses 24 hours after delivery. OAV's rule is "by the end of the next working day". Is the
   simplification acceptable for v1, or should the deadline use a working-day calendar?
4. **LeadDeliveryFailed.** Today it is recorded on the agent's own record only. Should it also notify the agent (their
   CRM is the thing failing, so the notice would need another channel)?

## 20. v2

1. Spring Boot 4.1 (with spring-kafka 4.x and Jackson 3).
2. `delivery` as a second deployable.
3. Land Registry late match for completions and completed price.
4. A static side-by-side page for the seller view.
5. CI with Testcontainers.
6. Optional EBM sidecar for the comparables range with an error estimate.
