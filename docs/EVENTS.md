# Events: the contract between modules

Every event travels in one JSON envelope (`outbox/EventEnvelope.java`, ARCHITECTURE.md §4):
`eventId` (uuidv7), `eventType`, `schemaVersion` (1), `occurredAt`, `requestId`, `leadId`, `agentId`,
`traceparent` (W3C, the span that wrote the event, see `observability/EventTracing.java`; null when tracing is off, as in tests), `payload`. Request-level events have `leadId` and `agentId` null; every lead-level event
carries both. Names are in `outbox/EventTypes.java`, topics in `outbox/Topics.java`, and each event has a JSON Schema
(draft 2020-12, unknown payload fields rejected) in `src/main/resources/events/<EventType>.schema.json`.
Tests check emitted events with `EventSchemas.assertValid(json)` (`src/test/java/uk/valuationloop/support/`).

| Event | Topic | Key | Producer | Consumer | Payload |
|---|---|---|---|---|---|
| ValuationRequested | `valuation.requested.v1` | requestId | request | none yet | postcode, outcode, propertyType, bedrooms |
| AgentsMatched | `valuation.requested.v1` | requestId | request | none yet | agentIds[], eligibilityRule (`slot-holders-for-outcode` \| `seller-selected`) |
| RequestClosed | `valuation.requested.v1` | requestId | delivery (ALL_LEADS_TERMINAL), comparison (SELLER_SELECTED) | none yet | reason |
| DeliverLead (command) | `lead.deliver.v1` (+ `lead.deliver.v1-retry-0`, `lead.deliver.v1-retry-1`, `lead.deliver.v1-dlt`) | leadId | request | delivery | adapter (`ok` \| `slow` \| `flaky`) |
| LeadDelivered | `lead.events.v1` | requestId | delivery | scorecard | adapter, attempt |
| LeadDeliveryFailed | `lead.events.v1` | requestId | delivery | scorecard | adapter, attempts, reason (agent-private, never on the seller page) |
| LeadReplied | `lead.events.v1` | requestId | response | scorecard | low, high, message (nullable), late, deliveredAt (nullable), repliedAt (the reply time the scorecard uses; `occurredAt` is when the event was written) |
| EvidenceAttached | `lead.events.v1` | requestId | evidence | scorecard | packId, comparablesCount, comparablesRange {low, mid, high} |
| LeadExpired | `lead.events.v1` | requestId | delivery | scorecard | replyDueAt |
| SellerEngaged | `lead.events.v1` | requestId | comparison | scorecard | action (`VIEWED` \| `CONTACTED` \| `VISIT_REQUESTED`) |
| SellerSelected | `lead.events.v1` | requestId | comparison | scorecard | none (the envelope's leadId is the choice) |
| VisitBooked | `lead.events.v1` | requestId | outcome | scorecard | visitAt (nullable) |
| InstructionRecorded | `outcome.recorded.v1` | requestId | outcome | scorecard | leadRef (the leadId the CRM echoes back), source, recordedAt |

## Retry topics
`@RetryableTopic` makes 3 attempts per lead: attempt 1 on `lead.deliver.v1`, attempt 2 on `lead.deliver.v1-retry-0`,
attempt 3 on `lead.deliver.v1-retry-1`; a lead that fails all three lands on `lead.deliver.v1-dlt` and becomes
`LeadDeliveryFailed`. So 3 attempts means 2 retry topics (constants in `Topics`: `LEAD_DELIVER_RETRY_0`,
`LEAD_DELIVER_RETRY_1`, `LEAD_DELIVER_DLT`).

## Ordering rule
Everything about one request is keyed by `requestId`, so it lands on one partition and a consumer sees it in order.
The one exception is the `DeliverLead` command, keyed by `leadId`: deliveries for different agents spread across
partitions, so one flaky agent's retries never block another agent's lead.

## Idempotency rule
Delivery is at-least-once (the outbox relay may resend after a crash), so every consumer must tolerate a repeat.
`eventId` is the idempotency key: a consumer whose side effect is not a natural-key upsert records it in
`processed_event`. The scorecard needs no dedupe table: it upserts `scorecard_fact` by `lead_id`, so applying the
same event twice, or replaying the topics from the start, leaves the same row.

## Changing an event
Adding an optional field is a schema edit plus a test. Renaming or removing a field, or changing its meaning, is a
breaking change: bump `schemaVersion` (or the topic suffix) instead of editing in place.
