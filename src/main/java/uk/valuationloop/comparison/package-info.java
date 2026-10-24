/**
 * Comparison: the seller's side-by-side page. GET .../comparison lists the replies in reply order, each with the
 * agent's own range, message and the evidence pack it attached (or null), plus a fixed disclaimer. Leads that did not
 * reply are not listed or counted; nothing scores, ranks or reorders agents. It is a SQL join over agent_lead and
 * evidence_pack, not a Kafka consumer (H2-review E2-1): both tables are in this database, so a join is always current.
 * POST .../engagement emits SellerEngaged; POST .../selection closes the request with SellerSelected + RequestClosed.
 * In one sentence: "the seller page reads the system of record directly; only the scorecard is built from events."
 */
package uk.valuationloop.comparison;
