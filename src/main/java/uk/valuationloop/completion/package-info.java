/**
 * Completion: the v2 Land Registry late match, the last join of Claim 3. Weeks after an instruction the sale appears
 * in Land Registry Price Paid Data; {@link uk.valuationloop.completion.CompletionScanner} looks for it and, when it
 * finds one, writes a completion_match row and a CompletionMatched event (outcome.recorded.v1, through the outbox) in
 * one transaction. The scorecard joins it to the lead and computes the gap between the completed price and the
 * midpoint of the range the reply quoted: a PRIVATE fact for the agent's own scorecard and the portal's internal
 * product query, never on the seller page.
 *
 * Honest limits of the demo: the match key is postcode + property type + "sold on or after the instruction minus 30
 * days" (400 in the demo profile, application-demo.yml), nothing more. In production the key would be the UPRN / normalised address under the operator's address
 * licence; the demo stores no address, so two flats sold in the same postcode in the same month are
 * indistinguishable to it. And the demo's request postcode is itself taken from a PPD sale, so the "late match" can
 * be that same sale matching itself (H2-review E2-6): it shows the join, not a discovery.
 */
package uk.valuationloop.completion;
