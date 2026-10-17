/**
 * Outcome: what happened after the reply. POST /agents/{id}/leads/{leadId}/visit moves the lead to VISIT_BOOKED and
 * emits VisitBooked. POST /outcomes/webhook is the simulated CRM push: it carries leadRef, the portal's own lead
 * reference echoed back, so attribution is a lookup by primary key rather than an address match (H2-review E2-6;
 * the address-based Land Registry late match is v2). It emits InstructionRecorded on outcome.recorded.v1, once:
 * a repeat for the same leadRef returns 200 and writes nothing.
 * Interview answer: "real CRMs echo our reference back, so joining an outcome to its lead is a key lookup, and the
 * lead's own instructed_at makes the webhook idempotent."
 */
package uk.valuationloop.outcome;
