package uk.valuationloop.dataloader;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import uk.valuationloop.evidence.ComparableDocument;
import uk.valuationloop.evidence.ComparablesQuery;
import uk.valuationloop.evidence.ComparablesRange;
import uk.valuationloop.evidence.EvidenceProperties;

/**
 * THE REAL NUMBER (ARCHITECTURE.md §0): how wide is the comparables range for a London flat or house?
 * Run with --loader.stats=true after a load.
 *
 * <pre>
 * population  every indexed FLAT or HOUSE sale in the configured outcodes (SW11, SW4, SW12, SW18, SW2, SW9, SE24, SE11)
 * sample      shuffle with a fixed seed (42), take the first N (400): reproducible
 * per sample  treat the sale as a request (its postcode centroid, type and floor area if it has one), run THE
 *             comparables query as of today with the sale itself excluded, take the range of the top 10
 * width       (high - low) / mid of that range; samples with fewer than 3 comparables have no range and are counted
 * report      median width and interquartile range (same quartile method as the range itself)
 * </pre>
 * This is why the product shows evidence and never a verdict: the honest spread of the comparables is wide.
 */
@Component
@ConditionalOnProperty(name = "loader.stats", havingValue = "true")
@EnableConfigurationProperties(LoaderProperties.class)
public class RangeWidthStatsRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RangeWidthStatsRunner.class);

    private final ElasticsearchClient client;
    private final EvidenceProperties evidence;
    private final LoaderProperties props;
    private final Clock clock;

    public RangeWidthStatsRunner(ElasticsearchClient client, EvidenceProperties evidence, LoaderProperties props,
                                 Clock clock) {
        this.client = client;
        this.evidence = evidence;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        List<ComparableDocument> population = population();
        List<ComparableDocument> sample = new ArrayList<>(population);
        Collections.shuffle(sample, new Random(props.statsSeed()));
        sample = sample.subList(0, Math.min(props.statsSample(), sample.size()));

        LocalDate asOf = LocalDate.ofInstant(clock.instant(), ZoneId.of("Europe/London"));
        List<Double> widths = new ArrayList<>(), flatWidths = new ArrayList<>(), houseWidths = new ArrayList<>();
        int noRange = 0;
        for (ComparableDocument subject : sample) {
            ComparablesQuery query = new ComparablesQuery(new ComparablesQuery.Params(subject.propertyType(),
                    subject.location(), subject.floorAreaM2(), asOf, subject.ppdTransactionId()), evidence);
            List<Long> prices = client.search(query.searchRequest(evidence.index()), ComparableDocument.class)
                    .hits().hits().stream().map(Hit::source).map(ComparableDocument::price).toList();
            ComparablesRange range = ComparablesRange.of(prices);
            if (range == null) {
                noRange++;
                continue;
            }
            widths.add(range.relativeWidth());
            ("FLAT".equals(subject.propertyType()) ? flatWidths : houseWidths).add(range.relativeWidth());
        }

        String summary = String.format(
                "RANGE WIDTH: population=%d sample=%d with-range=%d no-range(<3 comps)=%d | median (high-low)/mid=%.3f "
                        + "IQR=[%.3f, %.3f] | FLAT n=%d median=%.3f | HOUSE n=%d median=%.3f | asOf=%s seed=%d outcodes=%s",
                population.size(), sample.size(), widths.size(), noRange, q(widths, 0.5), q(widths, 0.25),
                q(widths, 0.75), flatWidths.size(), q(flatWidths, 0.5), houseWidths.size(), q(houseWidths, 0.5),
                asOf, props.statsSeed(), props.statsOutcodes());
        log.info(summary);
        System.out.println(summary);
    }

    /** Every FLAT/HOUSE doc in the outcodes, paged with search_after (sorted by id, so paging is stable). */
    private List<ComparableDocument> population() throws IOException {
        List<FieldValue> outcodes = props.statsOutcodes().stream().map(FieldValue::of).toList();
        List<FieldValue> types = List.of(FieldValue.of("FLAT"), FieldValue.of("HOUSE"));
        List<ComparableDocument> all = new ArrayList<>();
        List<FieldValue> after = null;
        while (true) {
            final List<FieldValue> cursor = after;
            SearchResponse<ComparableDocument> page = client.search(s -> {
                s.index(evidence.index()).size(5000)
                        .query(q -> q.bool(b -> b
                                .filter(f -> f.terms(t -> t.field("outcode").terms(tv -> tv.value(outcodes))))
                                .filter(f -> f.terms(t -> t.field("propertyType").terms(tv -> tv.value(types))))))
                        .sort(so -> so.field(fs -> fs.field("ppdTransactionId").order(SortOrder.Asc)));
                if (cursor != null) {
                    s.searchAfter(cursor);
                }
                return s;
            }, ComparableDocument.class);
            List<Hit<ComparableDocument>> hits = page.hits().hits();
            if (hits.isEmpty()) {
                return all;
            }
            hits.forEach(h -> all.add(h.source()));
            after = hits.get(hits.size() - 1).sort();
        }
    }

    private static double q(List<Double> values, double p) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        return ComparablesRange.quantile(values.stream().mapToDouble(Double::doubleValue).sorted().toArray(), p);
    }
}
