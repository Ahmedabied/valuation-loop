/**
 * Evidence: the comparables an agent can attach to a valuation reply (ARCHITECTURE.md §7).
 * One Elasticsearch function_score query ({@link uk.valuationloop.evidence.ComparablesQuery}): filter by property type,
 * radius and sale date, then gauss decays on distance, recency and floor area, plus a weight of 0.5 for comparables
 * with no floor area, because a decay on a missing field returns 1 and would otherwise rank an unknown as a perfect match.
 * The pack holds at most 10 comparables, their quartile range (comparablesRange, null below 3), the data vintage and the
 * provenance; the agent may remove comparables privately, then attach the pack to a REPLIED lead (EvidenceAttached via outbox).
 * Licence: packs carry PPD prices and dates, Code-Point distances and non-address EPC fields, with the three OGL
 * attributions (Provenance); never an address.
 * In one breath: "the agent asks, one query ranks real sales near the home, the agent curates, and the pack it attaches
 * says exactly where every number came from."
 */
package uk.valuationloop.evidence;
