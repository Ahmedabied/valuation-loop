package uk.valuationloop.evidence;

import co.elastic.clients.elasticsearch._types.GeoLocation;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE comparables query: one function_score query, nothing merged afterwards (ARCHITECTURE.md §7, H2-review E2-7).
 *
 * <pre>
 * filter (must all hold, no scoring): propertyType = X, within radiusM of the request point, sold in the last N months
 * score  = product of (score_mode multiply; boost_mode replace = ignore the filter's own score):
 *          gauss(location,       origin = request point, scale 400m)
 *          gauss(dateOfTransfer, origin = asOf,          scale 180d)
 *          gauss(floorAreaM2,    origin = request area,  scale 15, offset 5)    only when the request has an area
 *          weight 0.5 when the comparable has NO floorAreaM2                     only when the request has an area
 * </pre>
 *
 * Why the weight: a decay function on a field the document does not have returns 1, i.e. "perfect match" (V6).
 * Without the 0.5 a sale with no EPC floor area would outrank an otherwise identical sale whose area matches.
 * With it, the unknown-area sale ranks below the matched one and above one whose area is far off.
 *
 * Built with the Java client's typed builders: each {@code x -> x.field(..)} lambda fills one JSON object, the same
 * tree you would write by hand in Kibana. {@link #toJson()} prints that tree so a unit test can read it without ES.
 */
public final class ComparablesQuery {

    /**
     * What the query is about.
     * @param floorAreaM2          null when the request has no floor area: the floor-area function and weight are left out
     * @param asOf                 "today" for the date window and the recency decay (from the injected Clock, so tests can pin it)
     * @param excludeTransactionId a sale to leave out (the pseudo-request itself, in the range-width statistic); usually null
     */
    public record Params(String propertyType, GeoPoint origin, Double floorAreaM2, LocalDate asOf,
                         String excludeTransactionId) {}

    private final Params params;
    private final EvidenceProperties settings;

    public ComparablesQuery(Params params, EvidenceProperties settings) {
        this.params = params;
        this.settings = settings;
    }

    public Params params() {
        return params;
    }

    public Query query() {
        GeoLocation origin = GeoLocation.of(g -> g.latlon(ll -> ll.lat(params.origin().lat()).lon(params.origin().lon())));
        String oldestSale = params.asOf().minusMonths(settings.maxAgeMonths()).toString();

        Query filter = Query.of(q -> q.bool(b -> {
            b.filter(f -> f.term(t -> t.field("propertyType").value(params.propertyType())));
            b.filter(f -> f.geoDistance(gd -> gd.field("location").location(origin).distance(settings.radiusM() + "m")));
            b.filter(f -> f.range(r -> r.date(d -> d.field("dateOfTransfer").gte(oldestSale).lte(params.asOf().toString()))));
            if (params.excludeTransactionId() != null) {
                b.mustNot(m -> m.term(t -> t.field("ppdTransactionId").value(params.excludeTransactionId())));
            }
            return b;
        }));

        List<FunctionScore> functions = new ArrayList<>();
        functions.add(FunctionScore.of(fs -> fs.gauss(g -> g.geo(geo -> geo.field("location")
                .placement(p -> p.origin(origin).scale(settings.locationScaleM() + "m"))))));
        functions.add(FunctionScore.of(fs -> fs.gauss(g -> g.date(date -> date.field("dateOfTransfer")
                .placement(p -> p.origin(params.asOf().toString()).scale(Time.of(t -> t.time(settings.dateScaleDays() + "d"))))))));
        if (params.floorAreaM2() != null) {
            functions.add(FunctionScore.of(fs -> fs.gauss(g -> g.numeric(n -> n.field("floorAreaM2")
                    .placement(p -> p.origin(params.floorAreaM2()).scale(settings.floorAreaScaleM2())
                            .offset(settings.floorAreaOffsetM2()))))));
            // the one line H2-review E2-7 is about: no floor area -> half weight, in the same query
            functions.add(FunctionScore.of(fs -> fs
                    .filter(f -> f.bool(b -> b.mustNot(m -> m.exists(e -> e.field("floorAreaM2")))))
                    .weight(settings.missingFloorAreaWeight())));
        }

        return Query.of(q -> q.functionScore(fsq -> fsq
                .query(filter)
                .functions(functions)
                .scoreMode(FunctionScoreMode.Multiply)
                .boostMode(FunctionBoostMode.Replace)));
    }

    public SearchRequest searchRequest(String index) {
        return SearchRequest.of(s -> s.index(index).size(settings.size()).query(query()));
    }

    /** The query as the JSON Elasticsearch receives (the body of {"query": ...}). */
    public String toJson() {
        return JsonpUtils.toJsonString(query(), new JacksonJsonpMapper());
    }

    /** The parameters in plain words, for the pack's provenance: anyone can re-run the same query. */
    public Map<String, Object> describe() {
        Map<String, Object> d = new LinkedHashMap<>(); // keeps insertion order in the JSON, like a Python dict
        d.put("propertyType", params.propertyType());
        d.put("radiusM", settings.radiusM());
        d.put("maxAgeMonths", settings.maxAgeMonths());
        d.put("asOf", params.asOf().toString());
        d.put("size", settings.size());
        d.put("locationDecay", "gauss scale " + settings.locationScaleM() + "m");
        d.put("dateDecay", "gauss scale " + settings.dateScaleDays() + "d");
        if (params.floorAreaM2() != null) {
            d.put("floorAreaM2", params.floorAreaM2());
            d.put("floorAreaDecay", "gauss scale " + settings.floorAreaScaleM2() + " offset " + settings.floorAreaOffsetM2());
            d.put("missingFloorAreaWeight", settings.missingFloorAreaWeight());
        }
        d.put("scoreMode", "multiply");
        return d;
    }
}
