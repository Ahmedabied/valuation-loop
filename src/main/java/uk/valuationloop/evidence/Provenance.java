package uk.valuationloop.evidence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Where a pack's numbers came from, so anyone can check it: the licences the data is used under, the index, the
 * exact query parameters and when it was built.
 */
public record Provenance(List<String> attributions, String index, Map<String, Object> query, Instant builtAt) {

    /** Required by the Open Government Licence v3.0 for HM Land Registry Price Paid Data. */
    public static final String LAND_REGISTRY = "Contains HM Land Registry data © Crown copyright and database right 2026. "
            + "This data is licensed under the Open Government Licence v3.0.";

    /** Required by the OS OpenData licence for Code-Point Open (postcode centroids). */
    public static final String CODE_POINT = "Contains OS data © Crown copyright and database right 2026; "
            + "Contains Royal Mail data © Royal Mail copyright and database right 2026; "
            + "Contains National Statistics data © Crown copyright and database right 2026";

    public static final List<String> ATTRIBUTIONS = List.of(LAND_REGISTRY, CODE_POINT);
}
