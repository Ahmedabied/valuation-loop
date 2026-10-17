package uk.valuationloop.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * In-memory stand-ins for Elasticsearch: a search that returns whatever the test put in, and a centroid table.
 * The last query is kept so a test can check what the builder asked for.
 */
public final class FakeEvidenceSources {

    public static final GeoPoint ORIGIN = new GeoPoint(51.4600, -0.1700);

    public static class Search implements ComparablesSearch {
        public final List<ComparableDocument> results = new ArrayList<>();
        public ComparablesQuery lastQuery;

        @Override
        public List<ComparableDocument> search(ComparablesQuery query) {
            lastQuery = query;
            return List.copyOf(results);
        }
    }

    public static class Centroids implements PostcodeCentroids {
        @Override
        public Optional<GeoPoint> find(String postcode) {
            return postcode.startsWith("ZZ") ? Optional.of(ORIGIN) : Optional.empty();
        }
    }

    /** A fictional sale near ORIGIN; northMetres moves it north (about 111,195 m per degree of latitude). */
    public static ComparableDocument sale(String id, long price, Double floorArea, double northMetres) {
        return new ComparableDocument(id, price, "2026-05-01", "ZZ1 1ZZ", "ZZ1",
                new GeoPoint(ORIGIN.lat() + northMetres / 111_195.0, ORIGIN.lon()), "FLAT",
                floorArea == null ? null : "Mid-Terrace", false, "L", floorArea, floorArea == null ? null : "C",
                null, null, "WANDSWORTH", "ppd-2024-2026+epc-2026-09");
    }

    private FakeEvidenceSources() {}
}
