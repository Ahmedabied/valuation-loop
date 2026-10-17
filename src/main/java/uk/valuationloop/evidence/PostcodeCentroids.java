package uk.valuationloop.evidence;

import java.util.Optional;

/**
 * Postcode -> centroid. The loader writes Code-Point Open centroids (SW and SE areas) to a small Elasticsearch
 * index; {@link EsPostcodeCentroids} reads it. An interface so tests can use a fake.
 */
public interface PostcodeCentroids {

    /** @param postcode normalised, e.g. "SW11 1AA" */
    Optional<GeoPoint> find(String postcode);

    /** The document stored per postcode in the centroid index (id = the postcode). */
    record Centroid(String postcode, GeoPoint location) {}
}
