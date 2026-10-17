# LEARN: two days to own every line

For Ahmed, after the build. The goal of the two days: explain any line of this repo, change it without help, and
defend every design choice in an interview. Line counts are approximate. Paths are from the repo root; Java lives
under `src/main/java/uk/valuationloop/` and tests under `src/test/java/uk/valuationloop/`.

Setup once: `make services-up`, then `make test` (green), then `make run` in one terminal.

## Learn day 1

**Morning.** Run `make demo` and `make replay` <!-- verify after merge -->. Read the README and the RFC. Then follow
one request through the code with the walk-through below, the Django map in `../STUDY-TONIGHT.md` beside it.
**Afternoon.** The three break-and-fix exercises.
**Evening.** Write, in your own words, the answer to "walk me through what happens between commit and send". Check it
against RFC §7 only after you have written it.

## Learn day 2

**Morning.** Add one small feature end to end without help (see "The day 2 feature" below). Claude reviews only.
**Afternoon.** The 20 interview questions, out loud, then in writing. Rewrite the RFC's first page in your words.
**Evening.** Decide the application text and the AI answer (draft at the end). Nothing is sent without your yes.

## Reading order

| # | Read | Lines | Why |
|---|---|---|---|
| 1 | `README.md`, `docs/RFC-001-valuation-loop.md` | 200 + 350 | The claims and the shape |
| 2 | `docs/EVENTS.md`, `docs/adr/*.md` | 50 + 120 | The contract and the four decisions |
| 3 | Every `package-info.java` | 10 each | Each module in one breath |
| 4 | `lead/LeadState`, `LeadEvent`, `LeadTransitions`; `request/RequestState`, `RequestTransitions` | 25, 30, 45, 20, 40 | Both state machines as pure functions |
| 5 | `lead/LeadTransitionsTest`, `request/RequestTransitionsTest` | 60, 40 | How a transition table is tested |
| 6 | `src/main/resources/db/migration/V1__core_tables.sql`, `V2__evidence_pack.sql`, `V3__scorecard_fact.sql` | 70, 45, 30 | The tables, with comments |
| 7 | `request/ValuationRequestController`, `ValuationRequestService`, `ValuationRequest`; `agent/AgentDirectory` | 80, 105, 100, 40 | Step 1 of the loop |
| 8 | `outbox/EventEnvelope`, `OutboxWriter`, `OutboxEvent`, `OutboxRepository`, `OutboxRelay`, `Topics` | 35, 40, 75, 25, 90, 30 | The outbox |
| 9 | `outbox/OutboxRelayTest`, `request/ValuationRequestFlowTest` | 100, 155 | The outbox claim, the fan-out |
| 10 | `delivery/LeadDeliveryListener`, `LeadDeliveryService`, `DeliveryTransactions`, `OptimisticLockRetry`, `FlakyAdapter`, `DeadlineScanner`, `DeadlineService`; `request/RequestCloser` | 100, 55, 120, 65, 40, 70, 55, 65 | Claim 1 |
| 11 | `delivery/DeliveryRetryAndDltTest`, `DuplicateDeliveryTest`, `OutboxLatencyTest`, `DeadlineScannerTest`, `DeliveryIntegrationTestBase` | 100, 90, 30, 95, 135 | Claim 1's tests |
| 12 | `response/AgentLeadController`, `ReplyService`, `response/ReplyApiTest` | 60, 165, 155 | Reply, 409, 423 |
| 13 | `evidence/ComparablesQuery`, `ComparablesRange`, `EvidencePackBuilder`, `EvidenceService`, `Provenance` | 125, 45, 70, 185, 20 | Claim 2 |
| 14 | `evidence/ComparablesQueryTest`, `RangeTest`, `EvidenceApiTest`, `ComparablesRankingEsTest` | 75, 30, 215, 125 | Claim 2's tests |
| 15 | `comparison/ComparisonQuery`, `SellerActions`, `ComparisonController`, `comparison/ComparisonViewTest` | 90, 95, 60, 140 | The seller view |
| 16 | `outcome/OutcomeController`, `OutcomeService`, `WebhookTokenCheck`, `outcome/OutcomeApiTest` | 55, 80, 55, 140 | Visit, webhook, leadRef |
| 17 | `scorecard/ScorecardListener`, `ScorecardProjector`, `ScorecardFact`, `RangeRank`, `AgentScorecard`, `ScorecardRebuilder`, `ScorecardReplay`, `src/main/resources/sql/product-query.sql` | 55, 115, 170, 40, 65, 175, 100, 25 | Claim 3 |
| 18 | `scorecard/ScorecardReplayTest`, `ProductQueryTest` | 205, 75 | Claim 3's tests |
| 19 | `dataloader/ComparablesLoaderRunner`, `EpcLookup`, `AddressKey`, `OsgbToWgs84`, `RangeWidthStatsRunner` | 180, 210, 70, 130, 135 | The data and the real number |
| 20 | `api/ApiErrors`, `src/main/resources/application.yml`, `Makefile`, `build.gradle.kts` | 125, 110, 120, 45 | Plumbing |
| 21 | `simulator/` <!-- verify after merge --> | | `make demo` |

Steps 1 to 12 are day 1. Steps 13 to 21 are day 2 morning, before the feature.

## One request, top down

Follow `POST /valuation-requests` for a flat in SW11 until it shows up on the agent's scorecard.

1. **API.** `request/ValuationRequestController.create`. Django: a view plus a serializer. The nested `record
   CreateRequest` is the serializer; `@Valid` is `serializer.is_valid(raise_exception=True)`; a bad body becomes a 422
   in `api/ApiErrors`, which plays the part of an exception middleware.
2. **Service.** `request/ValuationRequestService.create`, `@Transactional`. Django: a function wrapped in
   `transaction.atomic()`. It builds the `ValuationRequest`, saves it and keeps the returned managed copy, looks up
   eligible agents in `agent/AgentDirectory` (the four SW11 slot holders: `ag_fictional_01`, `_02`, `_03`, `_05`),
   creates one `AgentLead` per agent, and writes six events: `ValuationRequested`, `AgentsMatched` and four
   `DeliverLead`.
3. **Aggregate.** `request/ValuationRequest.apply` and `lead/AgentLead` change state only through
   `RequestTransitions.next` and `LeadTransitions.next`. Django: model methods that refuse an invalid transition,
   like a small django-fsm without the library.
4. **Outbox.** `outbox/OutboxWriter.write` inserts each envelope into `outbox` in the same transaction. Django: an
   `Outbox.objects.create(...)` inside the same `atomic()` block, instead of calling Celery's `.delay()` there.
5. **Relay.** `outbox/OutboxRelay.tick` every 300 ms, then `OutboxRelayBatch.relayOnce`. Django: a Celery beat task
   doing `select_for_update(skip_locked=True)` on unsent rows, sending each and setting `sent_at`.
6. **Topic.** `DeliverLead` goes to `lead.deliver.v1`, key `leadId`; the other two go to `valuation.requested.v1`, key
   `requestId`. Django has no equivalent; think of a durable Celery queue you can re-read from any point.
7. **Consumer.** `delivery/LeadDeliveryListener.onDeliverLead`. Django: a Celery worker task with
   `autoretry_for` and a dead-letter queue, except the retry is a re-publish to `lead.deliver.v1-retry-0` and
   `-retry-1`, and the dead letter is `lead.deliver.v1-dlt` (`onDeadLetter`).
8. **Adapter.** `delivery/LeadDeliveryService.deliver`: `DeliveryTransactions.beginAttempt` (commit), then
   `AdapterRegistry.forName(...).send(...)` with no transaction open (`OkAdapter`, `SlowAdapter` or `FlakyAdapter`
   calling `FakeCrm`), then `DeliveryTransactions.recordDelivered` (commit, `LeadDelivered` to the outbox). Django:
   `requests.post` to a third-party API, kept outside `atomic()` on purpose.
9. **Reply.** `response/AgentLeadController.reply` then `ReplyService.reply`: the range, `LeadReplied` and the
   request's move to `REPLIES_IN` in one transaction; a second reply is 409. `GET .../contact` is 423 until then.
10. **Evidence.** `evidence/EvidenceController` then `EvidenceService.build`, `EvidencePackBuilder`,
    `EsComparablesSearch` running `ComparablesQuery`; the agent removes comparables and calls `attach`, which writes
    `EvidenceAttached`. Django: a view calling an Elasticsearch client directly, no ORM in between.
11. **View.** `comparison/ComparisonController.comparison` then `ComparisonQuery.forRequest`, one SQL join with
    `JdbcTemplate`. Django: a raw `connection.cursor()` query for one read-only page. `SellerActions.engage` and
    `select` write `SellerEngaged`, `SellerSelected` and `RequestClosed`.
12. **Outcome.** `outcome/OutcomeController.bookVisit` and `webhook`, then `OutcomeService`; the webhook looks the
    lead up by `leadRef`. Django: a webhook view with a token-checking decorator (`WebhookTokenCheck`).
13. **Scorecard.** `scorecard/ScorecardListener.onEvent` then `ScorecardProjector.apply`, an upsert of
    `scorecard_fact` by `lead_id`. Django: `update_or_create(lead_id=...)` in a consumer. `GET /agents/{id}/scorecard`
    returns `AgentScorecard.of(...)`. `make replay` runs `ScorecardRebuilder.rebuild`.

## Break and fix

Do each on a scratch branch (`git switch -c break-1`), then `git restore .` and `git switch -` to undo. Write down
what you saw before you fix it.

### 1. Delete an outbox write

Edit `request/ValuationRequestService.create`: delete the `outbox.write(Topics.LEAD_DELIVER, ...)` call inside the
`for` loop (the one that writes `DeliverLead`).

Run: `./gradlew test --tests '*ValuationRequestFlowTest' --tests '*DeliveryRetryAndDltTest'`.

Goes red: `ValuationRequestFlowTest.sw11RequestFansOutToEverySlotHolderAndNothingElse` (expects 6 outbox rows, finds
2) and `DeliveryRetryAndDltTest.flakyLeadReachesTheDltAfterThreeAttemptsAndTheOthersAreDelivered` (times out after
30 s: every lead stays `CREATED`).

Observe: the API still answers 201 and the request looks fine. The leads just never reach anyone, and nothing logs an
error. That is the silent loss the outbox exists to prevent: the event must be written in the same transaction as the
state, or it is not written at all.

Second half, the real kill and restart:
1. Start the app with the relay off:
   `PG_PASSWORD=$(cat ~/tools/pg.pw) SPRING_PROFILES_ACTIVE=local,demo ./gradlew bootRun --args="--valuationloop.outbox.enabled=false"`
2. Post a request:
   `curl -s -X POST 127.0.0.1:8080/valuation-requests -H 'Content-Type: application/json' -d '{"sellerRef":"learn-1","postcode":"SW4 7AA","propertyType":"FLAT","bedrooms":2}'`
3. Stop the app (Ctrl-C). The rows are committed and unsent: this is "the process died between commit and send".
4. In another terminal:
   `~/tools/kafka_2.13-4.3.1/bin/kafka-console-consumer.sh --bootstrap-server 127.0.0.1:9092 --topic valuation.requested.v1 --from-beginning --property print.key=true`
5. `make run`. Within a second the request's events appear, once each. Restart again: they do not appear twice.

Also try: remove the `isActualTransactionActive()` check in `outbox/OutboxWriter.write` and run
`./gradlew test --tests '*OutboxRelayTest'`; `writingOutsideATransactionFailsLoudly` goes red.

### 2. Make the flaky CRM always fail

Observe it live: start the app with
`PG_PASSWORD=$(cat ~/tools/pg.pw) SPRING_PROFILES_ACTIVE=local,demo ./gradlew bootRun --args="--valuationloop.delivery.flaky-failures=99"`,
post an SW11 request (`"postcode":"SW11 1AA"`), and tail the DLT:
`~/tools/kafka_2.13-4.3.1/bin/kafka-console-consumer.sh --bootstrap-server 127.0.0.1:9092 --topic lead.deliver.v1-dlt --from-beginning --property print.key=true`.
About 1.5 s later one record arrives, keyed by the flaky lead's id. `GET /agents/ag_fictional_03/leads` shows that lead
`DELIVERY_FAILED`; the other three agents' leads are `DELIVERED`. The app log shows the three attempts, and the
`LeadDeliveryFailed` event on `lead.events.v1` carries `"attempts": 3` and the cause (`CrmUnavailable`).

Then make a test go red: in `delivery/LeadDeliveryListener` change `attempts = "3"` to `attempts = "4"`. Run
`./gradlew test --tests '*DeliveryRetryAndDltTest'`. Both tests go red:
`springKafkaCreatedTheRetryAndDltTopicsWithTheseNames` now finds 5 topics (a `-retry-2` appears) and
`flakyLeadReachesTheDltAfterThreeAttemptsAndTheOthersAreDelivered` finds 4 attempts. This is the off-by-one in the
architecture note: N attempts means N - 1 retry topics.

### 3. Remove the missing-floor-area weight

Edit `evidence/ComparablesQuery.query`: delete the `functions.add(...)` whose filter is
`mustNot(m -> m.exists(e -> e.field("floorAreaM2")))` and whose weight is `missingFloorAreaWeight()` (four lines, under
the comment "the one line H2-review E2-7 is about").

Run: `./gradlew test --tests '*ComparablesQueryTest'`. `missingFloorAreaGetsHalfWeightInTheSameQuery` goes red
("a weight-only function" is null, and there are 3 functions, not 4).

With Elasticsearch up: `./gradlew test -Pes=true --tests '*ComparablesRankingEsTest'`. In
`floorAreaMatchRanksAboveMissingFloorArea`, `WITH-AREA` (68 m², the same as the request) and `NO-AREA` (no floor
area) now get the same score, because the decay returns 1 for the missing field. Their order is decided by
Elasticsearch's tie-breaking, so the test may fail or pass by luck. That is the bug: an unknown is treated as a
perfect match. Put the weight back and `NO-AREA` is always second.

## Java and Spring idioms to explain

- **Records.** `record Reply(BigDecimal low, BigDecimal high, String message) {}` is an immutable data class with
  constructor, accessors (`low()`), `equals`, `hashCode` and `toString` generated. It is Python's
  `@dataclass(frozen=True)`. Used here for request bodies, views, events and the pack.
- **Sealed interfaces and pattern switch.** `sealed interface LeadEvent` lists every permitted implementation, so the
  compiler knows the full set. `switch (event) { case LeadEvent.Deliver e -> ... }` must cover every case or it does
  not compile, so adding an event forces you to decide its transitions (`LeadTransitions`, `DeliveryTransactions.Begin`).
- **`@Transactional` and the proxy trap.** Spring wraps the bean in a proxy that opens and commits the transaction
  around the call. A call from one method to another inside the same class bypasses the proxy, so the inner
  `@Transactional` silently does nothing. That is why `OutboxRelay` and `OutboxRelayBatch`, and `LeadDeliveryService`
  and `DeliveryTransactions`, are separate beans.
- **`@Version` optimistic locking.** A `version` column is checked and incremented on every update; if another
  transaction changed the row first, the update matches zero rows and JPA throws. Nobody waits on a lock, the loser
  retries with fresh data (`OptimisticLockRetry`). Django has no built-in equivalent; it is a hand-written
  `filter(version=v).update(..., version=v+1)`.
- **JPA `save` returns the managed entity.** With an id you assigned yourself, `save()` merges and returns a different,
  managed instance; changes to the old object never reach the row. Keep the return value
  (`request = requests.save(request)`). Inside a transaction a loaded entity is managed and written at commit without
  any `save` (dirty checking).
- **`@KafkaListener` with ack-mode record.** The method is called once per record; when it returns without throwing,
  the container commits that record's offset. If it throws, the offset is not committed and the error handler (here,
  the retry topics) decides what happens. Committing after the database commit is what makes a crash safe.
- **`@RetryableTopic`.** Declares N attempts, backoff and a DLT on the listener; spring-kafka creates the retry topics
  and their listeners. A failed record is re-published to the next topic with a delay header, so the main partition
  keeps moving. `@DltHandler` is the method for the last stop.
- **`@Scheduled`.** Runs a method on a timer (`fixedDelay` waits that long after the previous run ends). It is Celery
  beat inside the same process. The relay and the deadline scanner use it; tests switch them off and call the method by
  hand.
- **`@ConfigurationProperties`.** Binds a block of `application.yml` to a typed object (`evidence/EvidenceProperties`,
  `dataloader/LoaderProperties`). It is `settings.py` with types and validation; `@Value("${...}")` is the one-value
  version used elsewhere.
- **Flyway vs Django migrations.** Flyway runs hand-written SQL files `V1__...sql`, `V2__...sql` in order and records
  them in a history table. Nothing is generated from the entities; `ddl-auto: validate` only checks the entities match.
  Never edit an applied migration; add `V4`.
- **MockMvc vs the Django test client.** `mvc.perform(post("/valuation-requests").content(...))
  .andExpect(status().isCreated())` drives the whole Spring MVC stack in process without a port, like
  `self.client.post(...)` and `assertEqual(response.status_code, 201)`.
- **`@EmbeddedKafka(kraft = true)`.** Starts a real Kafka broker inside the test JVM, no Docker, and points
  `spring.kafka.bootstrap-servers` at it. Tests that share the same annotations share one Spring context and broker,
  which keeps the suite fast.
- **`function_score` decay.** A gauss decay turns a distance from an origin into a score: 1 at the origin, 0.5 at
  `scale`, close to 0 far away; `offset` widens the perfect zone. Scores multiply across functions. On a missing field
  it returns 1, which is why the 0.5 weight exists.

## The day 2 feature

Suggested: `LeadWithdrawn`, the agent withdraws from a lead it cannot serve. Do it without help, in this order, and
make each step's test pass before the next:
1. The rule: from which states may a lead be withdrawn, and what does the seller page show? Write it down first.
2. `lead/LeadEvent`, `LeadState`, `LeadTransitions` and `LeadTransitionsTest`.
3. `outbox/EventTypes`, a new `src/main/resources/events/LeadWithdrawn.schema.json`, `docs/EVENTS.md`.
4. An endpoint and a `@Transactional` service method that changes state and writes the event in one transaction.
5. `request/RequestCloser`: is a withdrawn lead terminal?
6. `scorecard/ScorecardProjector.HANDLED` and what the fact records.
7. An API test in the style of `response/ReplyApiTest`.
Alternative: a new simulator scenario <!-- verify after merge -->.

## 20 interview questions

Model answers are short on purpose. Rewrite each in your own words before the interview; do not memorise these.

1. **Why a monolith?** The outbox hole in the four-service design, the memory budget, and that the split point is
   designed in. One transaction covers each state change and its event. `delivery` can become a second deployable in
   an evening because its inputs and outputs are already topics.
2. **Why not Kafka Streams?** The read model is a simple upsert into Postgres, where the API that reads it lives. Streams
   would add state stores, changelog topics and a second place to query. I would use it for windowed aggregations
   across topics at volume.
3. **Exactly-once or effectively-once?** Effectively-once. The relay sends at least once; the producer is idempotent
   per session, but a crash after send and before `sent_at` resends. Consumers make the repeat harmless by state or by
   natural-key upsert. Kafka transactions would not cover the Postgres write anyway.
4. **How is ordering kept?** Per key. Everything about one request is keyed by `requestId`, so it lands on one partition
   and one consumer sees it in order. The relay stops a batch on a failed send so later rows do not overtake. Across
   requests there is no order and none is needed.
5. **Why is DeliverLead keyed by leadId but events by requestId?** Delivery is per agent: keying by lead spreads one
   request's leads over partitions, so one flaky CRM's retries never delay another agent. Events are per request: the
   scorecard needs them in order for a request.
6. **Why no ranking of agents?** Agents are the paying customers, and a platform verdict on a named agent built on this
   data is a claim nothing could back. Technically, comparables disagree by a median 28% of value, so a 5 to 10%
   difference between two agents is noise. Eligibility is a lookup; the seller sees evidence in reply order.
7. **What about the data licences?** PPD and Code-Point Open are OGL with attribution strings, shown in the README and
   in every pack. EPC address fields are OS and Royal Mail restricted: they are hashed in loader memory for the join
   and never stored. Production would use Rightmove's own address licences.
8. **What does the demo not prove?** Business impact. Agents, CRMs, sellers and outcomes are simulated. It proves the
   mechanics and gives the product team the query to run on real traffic.
9. **What changes at 10x load?** More partitions and listener concurrency; several relay instances (SKIP LOCKED already
   allows it) or change-data-capture instead of polling; a partial index on unsent rows and deletion of sent rows;
   per-CRM timeouts and bulkheads; `delivery` as its own deployable; Elasticsearch replicas.
10. **Crash between send and mark?** The send reached Kafka but `sent_at` never committed, so the row is still unsent
    and goes again. The consumer sees two records with the same `eventId`; delivery skips a lead already delivered and
    the scorecard upsert writes the same row.
11. **Why is the listener not @Transactional?** If it were, the offset ack could happen before the database commit, and
    a failed commit would lose the record. The listener calls a transactional service through the proxy and returns;
    the container commits the offset only after the database commit. Also, the adapter call must not hold a
    transaction open.
12. **Why does upsert by lead_id make replay possible?** The row is a function of the events for that lead, and every
    rule is order-independent. Replaying from offset 0 into an empty table produces the same rows. With a dedupe table
    instead, a replay would skip every event as already seen.
13. **What does a decay function do with a missing field?** It returns 1, a perfect score. So the query adds a
    weight-only function, `must_not exists floorAreaM2`, weight 0.5, and the loader never writes a sentinel.
14. **How is the webhook authenticated, and how should it be?** Now: a shared secret in `X-Webhook-Token`, compared in
    constant time, checked before the body is read. In production: a per-CRM key and an HMAC signature over the body,
    which also proves which agent's CRM sent it, so it can only record outcomes for that agent's leads.
15. **Why 423 for the contact?** The agent is allowed to see the contact, just not yet. 403 would say "never"; 423
    Locked says the resource is locked and the body says what unlocks it: reply first. It is the one public OAV rule.
16. **Why Boot 3.5, and what does the upgrade touch?** The best-documented line for a first Spring project, with
    spring-kafka 3.3 and the Elasticsearch 8 client. It left open-source support on 2026-06-30, so the bump to 4.1 is v2
    day 1: version lines, Jackson 3 imports, spring-kafka 4.x, the Elasticsearch client line.
17. **How do retries avoid blocking other leads?** Non-blocking retry topics: a failed record is re-published to
    `-retry-0` with a delay and the main partition moves on. Lock conflicts are retried in process and never reach the
    retry topics.
18. **How do you test Kafka without Docker?** `@EmbeddedKafka(kraft = true)` runs a broker in the test JVM; H2 in
    PostgreSQL mode runs the same Flyway migrations. The two Elasticsearch tests need a real node and run with
    `-Pes=true`.
19. **What if Elasticsearch is down?** Building a pack returns 503 with the standard error body. Requests, delivery,
    replies, the seller view and the scorecard carry on; a reply without a pack shows `evidence: null`.
20. **What would a Rightmove engineering manager ask first?** Probably "what did you build and what did the AI build,
    and walk me through the outbox". Answer the first honestly (next section) and the second from memory, then offer to
    change something live.

## What I would say about AI pairing (draft, Ahmed decides)

This is a draft for the application question about how I use AI. It is not sent anywhere without Ahmed's yes.

> I built Valuation Loop with AI pairing, and I can show exactly how. I wrote the brief and the problem statement,
> then used Claude to run research and two competing designs, and had two separate AI reviewers attack them on product
> fit and on engineering. The architecture I locked came out of those reviews, and several of their findings are in
> the code: the hand-rolled outbox, the non-transactional listener, the one-query fix for missing floor areas. Claude
> drafted most of the Java, because this is my first Java project; I reviewed it against the design and the tests,
> and then spent two days learning it until I could explain every line and change it without help. What I learned
> about working this way: the AI is fast at code and at finding traps in a design, and the human job is the problem,
> the constraints, and deciding what is true enough to claim.

Before using it, check every sentence against what actually happened in the learn days, and cut anything that is not
yet true.
