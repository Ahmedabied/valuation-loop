/**
 * Scorecard: the one replayable read model (Claim 3). ScorecardListener consumes lead.events.v1 and
 * outcome.recorded.v1 and ScorecardProjector upserts scorecard_fact by lead_id, so a repeat or a replay needs no
 * dedupe table; every rule (flags only turn on, earliest reply wins, ranks recomputed from the set of ranges) makes
 * the row independent of arrival order. GET /agents/{id}/scorecard shows an agent its own numbers and a fixture
 * benchmark label, never anyone else's; ScorecardInternalController (demo/test profiles only) serves the portal's
 * own product query and the fact snapshot. ScorecardRebuilder is `make replay`: stop, truncate, reset offsets, start.
 * Interview answer: "facts are a pure function of the event log, so I can drop the table and replay the topics."
 */
package uk.valuationloop.scorecard;
