package uk.valuationloop.evidence;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns "a request for a FLAT at SW11 1AA, 68 m2" into a {@link Pack}: postcode -> centroid, run THE comparables
 * query, map hits to comparables, compute the range. No database and no HTTP here, so a unit test drives it with
 * two fakes ({@link ComparablesSearch}, {@link PostcodeCentroids}) and a fixed clock.
 */
@Component
public class EvidencePackBuilder {

    /** What the pack is for: the seller's property as recorded on the valuation request. */
    public record Subject(String postcode, String propertyType, BigDecimal floorAreaM2) {}

    private final ComparablesSearch search;
    private final PostcodeCentroids centroids;
    private final EvidenceProperties settings;
    private final Clock clock;

    public EvidencePackBuilder(ComparablesSearch search, PostcodeCentroids centroids, EvidenceProperties settings,
                               Clock clock) {
        this.search = search;
        this.centroids = centroids;
        this.settings = settings;
        this.clock = clock;
    }

    public Pack build(String packId, Subject subject) {
        GeoPoint origin = centroids.find(subject.postcode())
                .orElseThrow(() -> new UnknownPostcode(subject.postcode()));
        LocalDate asOf = LocalDate.ofInstant(clock.instant(), ZoneId.of("Europe/London"));
        Double floorArea = subject.floorAreaM2() == null ? null : subject.floorAreaM2().doubleValue();
        ComparablesQuery query = new ComparablesQuery(
                new ComparablesQuery.Params(subject.propertyType(), origin, floorArea, asOf, null), settings);

        List<ComparableDocument> hits = search.search(query);
        List<Comparable> comparables = hits.stream().map(doc -> Comparable.from(doc, origin)).toList();
        String vintage = hits.isEmpty() ? "no-comparables" : hits.get(0).dataVintage();
        Provenance provenance = new Provenance(Provenance.ATTRIBUTIONS, settings.index(), query.describe(),
                clock.instant());
        return withRange(packId, comparables, vintage, provenance);
    }

    /**
     * The agent removed one comparable: same pack, that comparable gone, range recomputed from what remains.
     * Nothing is re-queried, so the agent's curation is never silently undone by a fresh search.
     */
    public Pack without(Pack pack, String ppdTransactionId) {
        List<Comparable> remaining = pack.comparables().stream()
                .filter(c -> !c.ppdTransactionId().equals(ppdTransactionId)).toList();
        return withRange(pack.packId(), remaining, pack.dataVintage(), pack.provenance());
    }

    private static Pack withRange(String packId, List<Comparable> comparables, String vintage, Provenance provenance) {
        ComparablesRange range = ComparablesRange.of(comparables.stream().map(Comparable::price).toList());
        return new Pack(packId, comparables, range, range == null ? Pack.NO_RANGE_NOTE : null, vintage,
                ComparablesRange.METHOD, provenance);
    }

    /** The request postcode has no centroid (outside the loaded SW/SE areas, or not a live postcode). */
    public static class UnknownPostcode extends RuntimeException {
        public UnknownPostcode(String postcode) {
            super("no centroid for postcode " + postcode + " (only the loaded SW and SE areas are covered)");
        }
    }
}
