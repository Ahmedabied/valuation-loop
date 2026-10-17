/**
 * Data loader: Land Registry Price Paid (Wandsworth + Lambeth, category A) + Code-Point Open centroids + EPC attributes
 * -> the comparables-v1 and postcode-centroids-v1 Elasticsearch indices. Runs only with --loader.run=true (profile
 * "loader": ES only); --loader.stats=true prints the range-width statistic. The index serves the one comparables query,
 * whose 0.5 weight for a missing floor area is why floorAreaM2 is left out when EPC has no match, never a sentinel.
 * Licence rule: EPC address fields are hashed in the loader's memory for the join and dropped; they are never stored,
 * indexed, logged or committed (only match counts are logged).
 * In one breath: "stream the sales, place each on the map from its postcode, join the EPC floor area by a hashed
 * address key, and bulk-index the result."
 */
package uk.valuationloop.dataloader;
