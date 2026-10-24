/**
 * Delivery: gets each DeliverLead command to the agent's CRM adapter (ok / slow / flaky, simulated) and expires
 * leads nobody answered. Shape (H2-review E2-5): a non-transactional @KafkaListener calls the service through
 * Spring's proxy; the service commits "attempt started", calls the adapter with no transaction open, commits
 * "delivered"; only then does the listener return and the container commit the offset (ack-mode record).
 * Duplicates are harmless: a lead already DELIVERED or beyond is skipped. Optimistic-lock conflicts retry in-process.
 * Slow CRMs are isolated: lead.deliver.v1 and its retry/DLT topics have 6 partitions (DeliveryTopics), keyed by leadId,
 * read by 3 listener threads, and every adapter call has a 5 s deadline (AdapterCaller); a timeout is a failure.
 * Runs in the "worker" process when the jar is split (application-worker.yml), together with the deadline scanner,
 * the DLT handler and the outbox relay; the "api" process starts none of them.
 * In one sentence: "a failed call is re-published to lead.deliver.v1-retry-0, then -retry-1, with growing backoff,
 * so a flaky CRM's backoff never blocks the partition; after the third failure it lands in lead.deliver.v1-dlt, where the
 * DLT handler marks the lead DELIVERY_FAILED and emits an agent-private LeadDeliveryFailed. A slow CRM holds one of
 * three threads for at most five seconds, and the other agents' leads are on the other partitions."
 */
package uk.valuationloop.delivery;
