/**
 * Response: the agent's side of a lead. POST .../reply records the range in one transaction with LeadReplied
 * (outbox) and the request's move to REPLIES_IN; a second reply is 409, someone else's lead is 404, a bad range 422.
 * GET .../contact is the one public valuation-request rule: 423 Locked until the agent has replied, then a simulated contact
 * (no seller PII exists here). GET /agents/{id}/leads shows the agent its own leads and nobody else's.
 * Plain synchronous HTTP + SQL (no listener here): the asynchrony lives in delivery; this module only emits facts.
 * Interview answer for retries: "the reply is idempotent by state, not by retry: a repeat is a 409, never a second fact."
 */
package uk.valuationloop.response;
