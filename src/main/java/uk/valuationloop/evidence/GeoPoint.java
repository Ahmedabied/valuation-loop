package uk.valuationloop.evidence;

/**
 * A WGS84 point. Serialises as {"lat": .., "lon": ..}, which is one of the shapes an Elasticsearch geo_point accepts.
 * A Java record is like a frozen Python dataclass: constructor, getters (lat(), lon()), equals and hashCode for free.
 */
public record GeoPoint(double lat, double lon) {

    private static final double EARTH_RADIUS_M = 6_371_008.8; // mean Earth radius (IUGG)

    /** Great-circle (haversine) distance in metres; plenty accurate for "how far is this comparable" at 1.5 km. */
    public double distanceMetresTo(GeoPoint other) {
        double dLat = Math.toRadians(other.lat - lat);
        double dLon = Math.toRadians(other.lon - lon);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(other.lat))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
