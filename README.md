# Valuation Loop

> "By joining our team, you'll help homeowners pick the right agent for them and help agents get better at what they
> do." (Rightmove, Agent Select vacancy)

This is my attempt at that problem, to see whether I read it right.

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

All test paths are under `src/test/java/uk/valuationloop/`. There are 151 tests; the two live-Elasticsearch ones run
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

The same jar also runs as two processes (`make demo-split`): a `worker` with the delivery listener, its retry topics
and DLT, the deadline scanner and the outbox relay, and an `api` with everything else. The Land Registry late match
(`completion`) adds the completed price to the instructed lead's fact.

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
| `completion` | The Land Registry late match: instructed leads joined to their sale in Price Paid Data, `CompletionMatched`, the price gap | `CompletionScanner`, `MatchRule`, `CompletionService`, `EsCompletedSales` |
| `dataloader` | PPD + Code-Point Open + EPC-derived attributes into Elasticsearch, and the range-width statistic | `ComparablesLoaderRunner`, `EpcLookup`, `AddressKey`, `OsgbToWgs84`, `RangeWidthStatsRunner` |
| `api` | One JSON error shape and the `nosniff` header for every response | `ApiErrors`, `NoSniffFilter` |
| `simulator` | Scenario runner for `make demo` (ok, slow, flaky CRM, late reply) and the outbox-gap demo; `demo` profile only | `SimulatorScenarios`, `OutboxGapDemo`, `SimulatorController` |

## Run it

### Prerequisites

There is no Docker on the box this was built on, so everything runs from user-space binaries. The Makefile assumes
these paths; override the variables at the top of the Makefile if yours differ.

| Thing | Where the Makefile looks |
|---|---|
| JDK 21 | `~/jdks/jdk-21.0.12.1+1` by default; export `JAVA_HOME` to yours (the Makefile's default is that path) |
| Kafka 4.3.1 (KRaft) | `~/tools/kafka_2.13-4.3.1`, data in `~/tools/kafka-data` |
| Elasticsearch 8.19.4 | `~/tools/elasticsearch-8.19.4` (single node, security off, loopback only) |
| Postgres 18 | `~/tools/postgres` binaries, cluster in `~/tools/pgdata`, port 5433 |
| Data (only for `make load`) | `../data-raw/ppd/pp-2024.csv`, `pp-2025.csv`, `pp-2026.csv`; `../data-raw/codepoint/codepo_gb.zip`; `../data-raw/epc/epc-wandsworth-lambeth.csv` |
| Memory | about 4 GB free for Postgres + Kafka (512 MB heap) + Elasticsearch (1 GB heap) + the app |

Gradle comes from the wrapper (`./gradlew`, Gradle 8.14.3). Export `JAVA_HOME` to a JDK 21 before running
`./gradlew` directly. The build's JDK 21 toolchain is found via the `JDK21_HOME` environment variable, defaulting to
`JAVA_HOME` (`gradle.properties`: `org.gradle.java.installations.fromEnv=JDK21_HOME,JAVA_HOME`; the Makefile exports
`JDK21_HOME` = `JAVA_HOME`), so the Gradle daemon itself may run on another JDK.

### Steps

```
make test            # 151 tests: 149 run, the 2 live-Elasticsearch ones skip; H2 + @EmbeddedKafka, no services
make services-up     # Postgres on 5433, Kafka on 9092, Elasticsearch on 9200 (make status to check)
make test-es         # the same plus the two live-Elasticsearch tests (needs Elasticsearch up)
make load            # load Wandsworth + Lambeth into Elasticsearch (about 23 s; make demo runs it if the index is missing)
make stats           # print the range-width statistic above
make run             # the app on 127.0.0.1:8080 (actuator on 127.0.0.1:8081), profiles local,demo (demo adds the simulator and internal routes)
make demo            # starts the app from the jar, runs every scenario, prints the report, stops the app (not with make run up)
make replay          # after make demo, app stopped: truncate scorecard_fact, reset offsets, rebuild, compare, exit 0/1
make demo-reset      # clean numbers: stop app, Kafka, Postgres; delete ~/tools/kafka-data, ~/tools/pgdata, the snapshot
make demo-split      # the same jar as two processes: api on 8080 (actuator 8081), worker on 8090 (actuator 8091)
make run-split       # api + worker in the background (or make run-api / make run-worker); scripts/split.sh stop
make page ID=<id>    # print the URL of the seller's side-by-side page for a request (the app must be running)
make services-down
```

`make help` lists every target. `make clean-daemons` stops the Gradle daemon when you are done (it stops every daemon
of this Gradle version, so not while another session on the box is building). `make demo` and `make replay` start the
app themselves on 8080/8081 and refuse if `make run` holds those ports; they wait (up to 3 minutes) while less than
2.5 GB of RAM is free. A Gradle daemon kept from `make test` holds about 1 GB; stop it first on a tight box.

Clean numbers: run `make demo-reset` before a demo you will show someone; the database otherwise accumulates every
run. It stops the demo app, Kafka and Postgres, deletes `~/tools/kafka-data`, `~/tools/pgdata` and
`~/tools/logs/scorecard-snapshot.json`, and keeps the Elasticsearch data and `~/tools/pg.pw`; the next `make demo`
runs initdb, Flyway V1-V5 and `kafka-storage format --standalone` again. It refuses (exit 1, nothing deleted) if
Kafka or Postgres is still running on those directories after the stop.

Checked again on Spring Boot 4.1.1 on 2026-10-17 (second review), services stopped at the start: `make test` three
times (149 tests, 2 skipped, each run forced), `make services-up`, `make demo` twice (ok scenario `completed=true` as
a staged self-match, the subject's own sale removed from the pack first), `make replay` (`replay: IDENTICAL ...
(173 facts)`, exit 0), `make demo-split` (every delivery and the DLT in the worker, every scorecard line in the api),
`make page` and the comparison JSON for the ok request, `make test-es` (149 tests, both live tests pass),
`make services-down`.

### Side-by-side page

`src/main/resources/static/comparison.html` is the seller's view in a browser: one plain HTML file, vanilla JS, no
build step, no CDN. Spring Boot serves it at `/comparison.html` under every profile. It reads
`GET /valuation-requests/{id}/comparison` from the same origin and shows the disclaimer verbatim, then one column per
reply in reply order: the agent's range, message, reply time, and the attached evidence (comparables range, method,
data vintage, the past sales) or "No evidence attached". Its buttons post engagement (viewed, contact, visit) and the
selection. `make page ID=<requestId>` prints the URL; `docs/DEMO.md` says how to get a request id.
`ComparisonPageTest` checks it is served as HTML and contains none of the never-list words.

### CI and licence

[![CI](https://github.com/Ahmedabied/valuation-loop/actions/workflows/ci.yml/badge.svg)](https://github.com/Ahmedabied/valuation-loop/actions/workflows/ci.yml)
<!-- repo not public yet; badge activates after the first push -->

`.github/workflows/ci.yml` runs on every push and pull request. Job `test`: JDK 21 (Temurin), `./gradlew test` on H2
and embedded Kafka, no services. Job `test-es` (after `test`): an Elasticsearch 8.19.4 service container, then
`./gradlew test -Pes=true`, which adds the live-Elasticsearch tests. Test reports are uploaded when a job fails.
Dependabot checks Gradle dependencies weekly.

Code: MIT (see LICENSE). Data: see Data and licences above.

### Where logs live

- Kafka, Elasticsearch and Postgres: `~/tools/logs/kafka.log`, `es.log`, `pg.log`.
- The app: standard output of `make run`; under `make demo`, `~/tools/logs/app.log`, one ECS JSON object per line with
  `traceId` and `spanId` (the `local` profile). `grep <traceId> ~/tools/logs/app.log` follows one request across the
  API thread, the outbox relay and both consumers.
- `make demo` also writes `demo-report.txt` and `demo-comparison.json` there; `make replay` writes `replay.log`.
- `make replay` writes its snapshot to `~/tools/logs/scorecard-snapshot.json` and logs `IDENTICAL` or the rows that
  differ; it exits 1 if they differ.

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

Demo-only routes, not part of the product API (`@Profile`, absent from a build run without `demo`): `POST /simulator/scenarios/{name}`
and `POST /simulator/outbox-gap` (`demo`), `POST /completions/scan` (`demo`, `test`) and
`PUT|DELETE /internal/demo/delivery/flaky-failures`, `GET /internal/demo/delivery/dlt-records` (`demo`, used by the split run).

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
  refinement. The register licenses every field except the address and postcode fields under the Open Government
  Licence v3.0: "Contains public sector information licensed under the Open Government Licence v3.0." The
  certificate's address fields are used only in the loader's memory, as a hashed normalised address
  key for the join, and are never stored, indexed, logged or committed.
- Data vintage on every pack: `ppd-2024-2026+epc-2026-10`.
- **The EPC decision.** The EPC register limits use of its address fields to purposes related to the energy
  performance of buildings. This demo uses EPC addresses only in memory, to join floor area to sales, and is a
  non-commercial demonstration. A production deployment would use the operator's own address licences (Rightmove has
  them) or run with `loader.epc.enabled=false`, which the loader supports.
- The raw downloads live outside the repo (`../data-raw/`) and are never committed.

## Personal data

The system holds a pseudonymous `sellerRef` only. No seller names, emails or phone numbers exist anywhere in it: the
"contact" released after a reply is a simulated, non-routable token built from the `sellerRef`. No photos are stored.
An agent sees only its own leads and its own numbers. v1 has no retention period; the real product would set one by
its own data policy, and the RFC records that as a gap.

## Built with AI pairing

Built with AI pairing. I set the problem, the constraints and the acceptance tests for each claim; Claude (Anthropic)
drafted most of the Java, the documentation and the review passes under my direction. I am studying every line until
I can explain and change it.

## What it does not prove

- Business impact. Agents, CRMs, sellers and outcomes are simulated, so no number here says that evidence wins sellers.
  It shows the facts are recorded correctly and the query that would answer it.
- Production scale. One JVM, one Kafka broker, one Elasticsearch node, a laptop.
- Real CRM behaviour. Three simulated adapters stand in for many real integrations.
- Security. The webhook uses a shared secret and the agent API trusts the path; see the RFC for what production needs.

## Versions

| Piece | Version |
|---|---|
| Java | 21 |
| Spring Boot / Spring Framework | 4.1.1 / 7.0.9 |
| spring-kafka / kafka-clients | 4.1.1 / 4.2.1 (broker 4.3.1, KRaft) |
| Jackson | 3.1.5 (the Elasticsearch client alone uses Jackson 2.21) |
| Hibernate / Flyway | 7.4.5 / 12.4.0 |
| Elasticsearch / `elasticsearch-java` | 8.19.4 / 8.18.8 |
| Postgres | 18 (tests: H2 2.4.240 in PostgreSQL mode) |
| Gradle | 8.14.3, Kotlin DSL |

Upgraded from Boot 3.5.16 on 2026-10-17; see `docs/adr/0005-boot-4-upgrade-attempt.md`.

## v2

Done after v1 (2026-10-17): slow-CRM isolation (6 partitions, 3 listener threads, a 5 s adapter timeout), the
api/worker split from one jar, the Land Registry late match (postcode + type + date; demo-only self-match), the static
side-by-side page, CI on GitHub Actions, and the upgrade to Spring Boot 4.1.1 with Jackson 3 and spring-kafka 4.1
(versions in RFC §17, what changed in `docs/adr/0005-boot-4-upgrade-attempt.md`). What is left:

1. Testcontainers-based integration tests against real Postgres and Kafka (CI uses H2 and embedded Kafka today).
2. Optional EBM sidecar for the comparables range with an error estimate.
