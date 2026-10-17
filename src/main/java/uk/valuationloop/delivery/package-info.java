/**
 * Delivery: gets each DeliverLead command to the agent's CRM adapter (ok / slow / flaky, simulated) and expires
 * leads nobody answered. Shape (H2-review E2-5): a non-transactional @KafkaListener calls the service through
 * Spring's proxy; the service commits "attempt started", calls the adapter with no transaction open, commits
 * "delivered"; only then does the listener return and the container commit the offset (ack-mode record).
 * Duplicates are harmless: a lead already DELIVERED or beyond is skipped. Optimistic-lock conflicts retry in-process.
 * Interview answer: "a failed call is re-published to lead.deliver.v1-retry-0, then -retry-1, with growing backoff,
 * so a flaky CRM's backoff never blocks the partition; after the third failure it lands in lead.deliver.v1-dlt, where the
 * DLT handler marks the lead DELIVERY_FAILED and emits an agent-private LeadDeliveryFailed."
 */
package uk.valuationloop.delivery;
