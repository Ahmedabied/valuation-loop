package uk.valuationloop.dataloader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import uk.valuationloop.evidence.ComparableDocument;
import uk.valuationloop.evidence.EvidenceProperties;
import uk.valuationloop.evidence.GeoPoint;
import uk.valuationloop.evidence.PostcodeCentroids.Centroid;
import uk.valuationloop.request.Postcodes;

/**
 * The data load, run once with --loader.run=true (ApplicationRunner = "run this after the app has started";
 * @ConditionalOnProperty means the bean does not even exist otherwise, so a normal start never loads).
 *
 * <pre>
 * 1. stream the PPD yearly files, keep Wandsworth + Lambeth category A (A/C/D rules in PpdTransactions)
 * 2. read Code-Point Open centroids for the SW and SE areas, OSGB36 -> WGS84
 * 3. read the EPC file into hash-keyed attributes (EpcLookup; addresses never leave that step)
 * 4. one ComparableDocument per kept sale, bulk-indexed into comparables-v1
 * 5. the centroids into postcode-centroids-v1, for the pack builder's postcode lookup
 * </pre>
 * Logs counts only: never an address, never a postcode from the EPC file.
 */
@Component
@ConditionalOnProperty(name = "loader.run", havingValue = "true")
@EnableConfigurationProperties(LoaderProperties.class)
public class ComparablesLoaderRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ComparablesLoaderRunner.class);
    private static final int PROGRESS_EVERY = 10_000;

    private final LoaderProperties props;
    private final EvidenceProperties evidence;
    private final EsIndexWriter writer;

    public ComparablesLoaderRunner(LoaderProperties props, EvidenceProperties evidence, EsIndexWriter writer) {
        this.props = props;
        this.evidence = evidence;
        this.writer = writer;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        long started = System.nanoTime();

        PpdTransactions sales = new PpdTransactions(new HashSet<>(props.districts()));
        long malformed = 0;
        for (String file : props.ppdFiles()) {
            malformed += readPpd(Path.of(file), sales);
        }
        log.info("PPD: rows read={} malformed={} kept={}", sales.rowsSeen(), malformed, sales.size());

        Map<String, GeoPoint> centroids = CodePointReader.read(Path.of(props.codepointZip()), props.codepointAreas());
        log.info("Code-Point: centroids loaded={} for areas {}", centroids.size(), props.codepointAreas());

        EpcLookup epc = EpcLookup.load(Path.of(props.epcFile()));
        log.info("EPC: certificates read={} latest lodgement={}", epc.certificates(), epc.latestLodgement());

        String vintage = dataVintage(sales, epc);
        writer.ensureIndex(evidence.index(), "/es/comparables-v1.json", props.recreate());
        writer.ensureIndex(evidence.centroidIndex(), "/es/postcode-centroids-v1.json", props.recreate());

        Map<EpcLookup.Tier, Integer> tiers = new EnumMap<>(EpcLookup.Tier.class);
        Map<String, ComparableDocument> batch = new LinkedHashMap<>();
        int indexed = 0, noCentroid = 0;
        for (PpdRow sale : sales.rows()) {
            GeoPoint point = centroids.get(normalisedOrNull(sale.postcode()));
            if (point == null) {
                noCentroid++;   // terminated or brand-new postcode Code-Point does not have yet
                continue;
            }
            EpcLookup.Match match = epc.find(sale);
            tiers.merge(match.tier(), 1, Integer::sum);
            batch.put(sale.transactionId(), ComparableDocuments.from(sale, match.attributes(), point, vintage));
            if (batch.size() == props.bulkSize()) {
                writer.bulk(evidence.index(), batch);
                indexed += batch.size();
                batch.clear();
                if (indexed % PROGRESS_EVERY == 0) {
                    log.info("progress: {} docs indexed", indexed);
                }
            }
        }
        writer.bulk(evidence.index(), batch);
        indexed += batch.size();
        writer.refresh(evidence.index());

        int centroidDocs = indexCentroids(centroids);

        int matched = indexed - tiers.getOrDefault(EpcLookup.Tier.NONE, 0);
        log.info("LOADER DONE in {}s: rows read={}, kept={}, no centroid={}, EPC matched={} ({}%; full={}, short={}, identity={}), "
                        + "docs indexed={} into {}, centroids indexed={} into {}, dataVintage={}",
                (System.nanoTime() - started) / 1_000_000_000, sales.rowsSeen(), sales.size(), noCentroid, matched,
                String.format("%.1f", indexed == 0 ? 0.0 : 100.0 * matched / indexed),
                tiers.getOrDefault(EpcLookup.Tier.FULL, 0), tiers.getOrDefault(EpcLookup.Tier.SHORT, 0),
                tiers.getOrDefault(EpcLookup.Tier.IDENTITY, 0), indexed, evidence.index(), centroidDocs,
                evidence.centroidIndex(), vintage);
    }

    /** Streams one file line by line (never the whole file in memory). Returns the malformed-line count. */
    private static long readPpd(Path file, PpdTransactions sales) throws IOException {
        long malformed = 0;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                Optional<PpdRow> row = PpdRowParser.parse(line);
                if (row.isPresent()) {
                    sales.apply(row.get());
                } else {
                    malformed++;
                }
            }
        }
        return malformed;
    }

    private int indexCentroids(Map<String, GeoPoint> centroids) throws IOException {
        Map<String, Centroid> batch = new LinkedHashMap<>();
        int written = 0;
        for (Map.Entry<String, GeoPoint> e : centroids.entrySet()) {
            batch.put(e.getKey(), new Centroid(e.getKey(), e.getValue()));
            if (batch.size() == props.bulkSize()) {
                writer.bulk(evidence.centroidIndex(), batch);
                written += batch.size();
                batch.clear();
            }
        }
        writer.bulk(evidence.centroidIndex(), batch);
        written += batch.size();
        writer.refresh(evidence.centroidIndex());
        return written;
    }

    /** e.g. "ppd-2024-2026+epc-2026-09": the PPD years present and the latest EPC lodgement month. */
    static String dataVintage(PpdTransactions sales, EpcLookup epc) {
        int first = sales.rows().stream().mapToInt(r -> r.dateOfTransfer().getYear()).min().orElse(0);
        int last = sales.rows().stream().mapToInt(r -> r.dateOfTransfer().getYear()).max().orElse(0);
        return "ppd-" + first + "-" + last + "+epc-" + epc.latestLodgement();
    }

    private static String normalisedOrNull(String postcode) {
        try {
            return Postcodes.normalise(postcode);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
