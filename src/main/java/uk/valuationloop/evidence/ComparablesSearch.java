package uk.valuationloop.evidence;

import java.util.List;

/**
 * Runs a comparables query and returns the hits best first. An interface (a Python Protocol) so the pack builder
 * can be tested with an in-memory fake; {@link EsComparablesSearch} is the Elasticsearch implementation.
 */
public interface ComparablesSearch {

    List<ComparableDocument> search(ComparablesQuery query);

    /** Thrown when the search backend cannot be reached; the API answers 503. */
    class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
