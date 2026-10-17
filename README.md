# Valuation Loop

I built the backend for Rightmove's choose-an-agent moment: a valuation lead that reaches each agent reliably, comes
back with evidence the agent chose to attach, records what the homeowner did with it and whether it became a visit
and an instruction, and gives the agent a private view of their own numbers, in Rightmove's stack (Java 21, Spring
Boot, Kafka, Elasticsearch, Postgres, Gradle).

Property data is real (HM Land Registry Price Paid Data and EPC certificates for Wandsworth and Lambeth). Agents,
CRMs, sellers and outcomes are simulated. The repo proves mechanics and hands a product team an instrument. It does
not prove business impact.

The design is in [docs/RFC-001-valuation-loop.md](docs/RFC-001-valuation-loop.md), the four big decisions are in
[docs/adr/](docs/adr/), and the event contract is in [docs/EVENTS.md](docs/EVENTS.md).

## The three claims, and the test that proves each

| # | Claim | Proved by |
|---|---|---|
| 1 | A valuation lead reaches each selected agent through an unreliable CRM adapter (retries, dead letter topic), and reply time and "evidence attached" are facts in an idempotent log. | `delivery/DeliveryRetryAndDltTest.flakyLeadReachesTheDltAfterThreeAttemptsAndTheOthersAreDelivered`, `delivery/DuplicateDeliveryTest.theSameDeliverLeadTwiceDeliversOnce`, `delivery/OutboxLatencyTest.firstEventIsOnTheTopicWithinOneSecondOfTheApiCall`, `outbox/OutboxRelayTest.eventCommitsWithTheTransactionAndIsSentExactlyOnceByTheRelay` |
| 2 | Every reply can carry an evidence pack built to one standard from real public data, chosen and curated by the agent, machine-readable, with provenance. | `evidence/ComparablesQueryTest.missingFloorAreaGetsHalfWeightInTheSameQuery`, `evidence/ComparablesRankingEsTest.floorAreaMatchRanksAboveMissingFloorArea` and `packFromLiveEsHasProvenanceAndAttachEmitsASchemaValidEvent` (live Elasticsearch) |
| 3 | The seller's choice, the visit and the instruction join back to the lead even when late and out of order, and one replayable query answers "do evidenced answers win the seller, and at what price gap?". | `scorecard/ScorecardReplayTest.outOfOrderLateAndDuplicateEventsProjectDeterministicallyAndReplayRebuildsIdenticalFacts`, `scorecard/ProductQueryTest.sharesCapturedByTheHighestRangeReplySplitByEvidence` |

All test paths are under `src/test/java/uk/valuationloop/`. There are 120 tests; the two live-Elasticsearch ones run
only with `make test-es` (`./gradlew test -Pes=true`).

## The real number

For 400 random flat and house sales (seed 42) in SW11, SW4, SW12, SW18, SW2, SW9, SE24 and SE11, each sale was used
as a pseudo-request against the same comparables query the product uses, with the sale itself excluded. For each one
I took the top 10 comparables and measured the width of their range: (upper quartile - lower quartile) / median.

| Sample | n | Median width | Interquartile range of the width |
|---|---|---|---|
| All | 400 | 0.280 | 0.191 to 0.373 |
| Flats | 283 | 0.299 | |
| Houses | 117 | 0.219 | |

When the comparables themselves disagree by 28% of value, no single number earns a verdict. That is why the product
shows the evidence each agent attached and never judges the agent. Reproduce it with `make load` then `make stats`.

## How it fits together

```
seller                         this app (one Spring Boot JVM)                                 agent / CRM
POST /valuation-requests ----> request: eligibility lookup, one lead per agent
                               state change + events in ONE transaction -> outbox table
                               OutboxRelay (every 300 ms) -> Kafka
                                   lead.deliver.v1 (key leadId) -> delivery listener
                                     -retry-0 -> -retry-1 -> -dlt   ----------------------->  simulated CRM
                                                                                              (ok / slow / flaky)
                               response: reply (409 on a second one), contact 423 until reply <- reply
                               evidence: one ES function_score query -> pack -> attach       <- agent curates
GET  .../comparison  <-------- comparison: SQL join over agent_lead + evidence_pack
POST .../engagement, selection -> SellerEngaged, SellerSelected, RequestClosed
                               outcome: visit, webhook with leadRef                           <- CRM webhook
                               lead.events.v1 + outcome.recorded.v1 (key requestId)
                                   -> scorecard listener -> scorecard_fact (upsert by lead_id)
                               GET /agents/{id}/scorecard ----------------------------------> own numbers only
Postgres = system of record     Kafka = the asynchronous seams     Elasticsearch = comparables
```

## Modules

Packages under `src/main/java/uk/valuationloop/`. Each module with a `package-info.java` has a short note written to
be read first.

| Package | What it does | Key classes |
|---|---|---|
| `request` | Takes the seller's request, looks up eligible agents, creates one lead per agent, writes the events | `ValuationRequestController`, `ValuationRequestService`, `ValuationRequest`, `RequestTransitions`, `RequestCloser` |
| `lead` | The `AgentLead` aggregate and its state machine, shared by the modules that change a lead | `AgentLead`, `LeadState`, `LeadEvent`, `LeadTransitions` |
| `agent` | Fixture agents (`ag_fictional_01` to `_06`) and the outcode slot lookup | `AgentDirectory` |
| `outbox` | Outbox table, relay, envelope, topic and event names | `OutboxWriter`, `OutboxRelay`, `EventEnvelope`, `Topics`, `EventTypes` |
| `delivery` | `lead.deliver.v1` consumer, retry topics and DLT, simulated adapters, deadline scanner | `LeadDeliveryListener`, `LeadDeliveryService`, `DeliveryTransactions`, `FlakyAdapter`, `DeadlineScanner`, `OptimisticLockRetry` |
| `response` | The agent's reply, contact release after reply, the agent's own lead list | `AgentLeadController`, `ReplyService` |
| `evidence` | The comparables query, pack builder, curation and attach | `ComparablesQuery`, `EvidencePackBuilder`, `EvidenceService`, `ComparablesRange`, `Provenance` |
| `comparison` | The seller's side-by-side view and the seller's actions | `ComparisonQuery`, `SellerActions`, `ComparisonController` |
| `outcome` | Visit booked by the agent, instruction by CRM webhook carrying `leadRef` | `OutcomeController`, `OutcomeService`, `WebhookTokenCheck` |
| `scorecard` | The replayable read model, the agent's private scorecard, the product query, replay | `ScorecardListener`, `ScorecardProjector`, `ScorecardRebuilder`, `ScorecardReplay`, `ProductQuery` |
| `dataloader` | PPD + Code-Point Open + EPC-derived attributes into Elasticsearch, and the range-width statistic | `ComparablesLoaderRunner`, `EpcLookup`, `AddressKey`, `OsgbToWgs84`, `RangeWidthStatsRunner` |
| `api` | One JSON error shape and the `nosniff` header for every response | `ApiErrors`, `NoSniffFilter` |
| `simulator` | Scenario runner for `make demo` (ok, slow, flaky CRM, late reply) <!-- verify after merge --> | <!-- verify after merge --> |

## Run it

### Prerequisites

There is no Docker on the box this was built on, so everything runs from user-space binaries. The Makefile assumes
these paths; override the variables at the top of the Makefile if yours differ.

| Thing | Where the Makefile looks |
|---|---|
| JDK 21 | `~/jdks/jdk-21.0.12.1+1` (`JAVA_HOME` is set by the Makefile) |
| Kafka 4.3.1 (KRaft) | `~/tools/kafka_2.13-4.3.1`, data in `~/tools/kafka-data` |
| Elasticsearch 8.19.4 | `~/tools/elasticsearch-8.19.4` (single node, security off, loopback only) |
| Postgres 18 | `~/tools/postgres` binaries, cluster in `~/tools/pgdata`, port 5433 |
| Data (only for `make load`) | `../data-raw/ppd/pp-2024.csv`, `pp-2025.csv`, `pp-2026.csv`; `../data-raw/codepoint/codepo_gb.zip`; `../data-raw/epc/epc-wandsworth-lambeth.csv` |
| Memory | about 4 GB free for Postgres + Kafka (512 MB heap) + Elasticsearch (1 GB heap) + the app |

Gradle comes from the wrapper (`./gradlew`, Gradle 8.14.3).

### Steps

```
make test            # 120 tests: 118 run, the 2 live-Elasticsearch ones skip; H2 + @EmbeddedKafka, no services
make services-up     # Postgres on 5433, Kafka on 9092, Elasticsearch on 9200 (make status to check)
make test-es         # the same plus the two live-Elasticsearch tests (needs Elasticsearch up)
make load            # load Wandsworth + Lambeth into Elasticsearch (about 23 s)
make stats           # print the range-width statistic above
make run             # the app on 127.0.0.1:8080 (actuator on 127.0.0.1:8081), profiles local,demo
make demo            # scenario run, comparison view and scorecard printed
make replay          # stop the scorecard consumer, truncate, reset offsets, rebuild, compare with a snapshot
make services-down
```

`make help` lists every target. `make clean-daemons` stops the Gradle daemon when you are done.

<!-- verify after merge: the make demo and make replay steps, from a cold start, against the merged Makefile -->

### Where logs live

- Kafka, Elasticsearch and Postgres: `~/tools/logs/kafka.log`, `es.log`, `pg.log`.
- The app: standard output of `make run`.
- `make replay` writes its snapshot to `~/tools/logs/scorecard-snapshot.json` and logs `IDENTICAL` or the rows that
  differ; it exits 1 if they differ.

<!-- verify after merge: app log format (JSON with traceId) and any demo log file -->

## API

The API binds to `127.0.0.1` only. v1 has no authentication: the `agentId` in the path stands in for the logged-in
agent and every query is scoped to it, so another agent's lead is a 404. Errors are always
`{"error": "<code>", "message": "<text>"}`.

| # | Method and path | What it does |
|---|---|---|
| 1 | `POST /valuation-requests` | Seller asks for valuations. 201; 422 for a bad postcode, an unknown agent or no slot holder |
| 2 | `GET /valuation-requests/{id}` | The request and its leads |
| 3 | `GET /valuation-requests/{id}/comparison` | Replies in reply order with the evidence each agent attached, plus a disclaimer |
| 4 | `POST /valuation-requests/{id}/engagement` | Seller viewed, contacted or asked for a visit. 202, emits `SellerEngaged` |
| 5 | `POST /valuation-requests/{id}/selection` | Seller selects an agent. Closes the request; a second selection is 409 |
| 6 | `POST /agents/{agentId}/leads/{leadId}/reply` | Agent replies with a range. 201; 409 on a second reply |
| 7 | `GET /agents/{agentId}/leads/{leadId}/contact` | Seller contact (simulated). 423 Locked until the agent replies |
| 8 | `GET /agents/{agentId}/leads` | The agent's own leads |
| 9 | `POST /agents/{agentId}/leads/{leadId}/evidence` | Build an evidence pack from Elasticsearch. 201; 503 if Elasticsearch is down |
| 10 | `GET /agents/{agentId}/leads/{leadId}/evidence/{packId}` | Read a pack and the agent's private exclusions |
| 11 | `DELETE /agents/{agentId}/leads/{leadId}/evidence/{packId}/comparables/{ppdTransactionId}` | Remove one comparable; the range is recomputed. 409 once attached |
| 12 | `POST /agents/{agentId}/leads/{leadId}/evidence/{packId}/attach` | Attach the pack to the reply. 409 unless the lead is REPLIED, and 409 for a pack with no range |
| 13 | `POST /agents/{agentId}/leads/{leadId}/visit` | Agent books a valuation visit. 409 before a reply or when already booked |
| 14 | `POST /outcomes/webhook` | CRM reports an instruction with `leadRef`. Needs `X-Webhook-Token`; 202 first time, 200 on a repeat, 401 without the token |
| 15 | `GET /agents/{agentId}/scorecard` | The agent's own numbers next to a labelled fixture benchmark |
| 16 | `GET /scorecard/product-query` | The product query. Exists only under the `demo` and `test` profiles |
| 17 | `GET /scorecard/snapshot` | Every scorecard fact, for the replay diff. Exists only under the `demo` and `test` profiles |

## Data and licences

- **HM Land Registry Price Paid Data**, 2024 to 2026, category A (standard sales), Wandsworth and Lambeth.
  2,291,778 rows read, 20,882 sales indexed.
  "Contains HM Land Registry data © Crown copyright and database right 2026. This data is licensed under the Open
  Government Licence v3.0."
- **OS Code-Point Open** postcode centroids: 41,080 postcodes, converted from OSGB36 eastings and northings to WGS84
  latitude and longitude in `dataloader/OsgbToWgs84` from the constants in the OS guide.
  "Contains OS data © Crown copyright and database right 2026; Contains Royal Mail data © Royal Mail copyright and
  database right 2026; Contains National Statistics data © Crown copyright and database right 2026"
- **EPC domestic certificates**: 90.2% of indexed sales matched to a certificate. Only OGL-licensed derived fields
  are indexed: floor area, energy rating, built form, construction age, habitable rooms and a property type
  refinement. The certificate's address fields are used only in the loader's memory, as a hashed normalised address
  key for the join, and are never stored, indexed, logged or committed.
- Data vintage on every pack: `ppd-2024-2026+epc-2026-10`.
- In production Rightmove would use its own address licences. This demo relies on the non-commercial permission.
- The raw downloads live outside the repo (`../data-raw/`) and are never committed.

## Personal data

The system holds a pseudonymous `sellerRef` only. No seller names, emails or phone numbers exist anywhere in it: the
"contact" released after a reply is a simulated, non-routable token built from the `sellerRef`. No photos are stored.
An agent sees only its own leads and its own numbers. v1 has no retention period; the real product would set one by
its own data policy, and the RFC records that as a gap.

## Built with AI pairing

Built with AI pairing: I designed and reviewed, Claude drafted most of the Java; every line is mine to defend. I am a
Python and Django engineer and this is my first Java and Spring project. The design came first: research, two
competing designs and two hostile reviews, kept outside this repo. The code and these docs were then written in
pairing sessions. [docs/LEARN.md](docs/LEARN.md) is the plan I follow to be able to explain and change every part of
it without help.

## What it does not prove

- Business impact. Agents, CRMs, sellers and outcomes are simulated, so no number here says that evidence wins sellers.
  It shows the facts are recorded correctly and the query that would answer it.
- Production scale. One JVM, one Kafka broker, one Elasticsearch node, a laptop.
- Real CRM behaviour. Three simulated adapters stand in for many real integrations.
- Security. The webhook uses a shared secret and the agent API trusts the path; see the RFC for what production needs.

## v2

1. Bump to Spring Boot 4.1 (Boot 3.5 left open-source support on 2026-06-30).
2. Split `delivery` into a second deployable; the Kafka seam already exists.
3. Land Registry late match: join completions back to the lead by address, months later.
4. A static side-by-side page for the seller view.
5. CI with Testcontainers.
6. Optional EBM sidecar for the comparables range with an error estimate.
