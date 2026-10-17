package uk.valuationloop.dataloader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * EPC attributes keyed by address HASH, built once from the filtered EPC CSV, then asked "what do you know about
 * this PPD sale's address?". Raw address strings exist only inside {@link #load} for the line being read.
 *
 * Three match tiers, tried in order; the first hit wins (deterministic):
 * <ol>
 *   <li>full: postcode + normalised(ADDRESS1, ADDRESS2, ADDRESS3) = postcode + normalised(SAON, PAON, street)</li>
 *   <li>short: the same with ADDRESS1 + ADDRESS2 only (EPC often puts the locality in ADDRESS3)</li>
 *   <li>identity: postcode + the dwelling-identifying tokens (numbers, single letters), used only when exactly one
 *       distinct EPC address in that postcode has those tokens; ambiguous identities are dropped, never guessed</li>
 * </ol>
 * Several certificates per address are normal (re-assessments); each key keeps the latest LODGEMENT_DATE.
 */
public final class EpcLookup {

    /** How each PPD row matched, for the summary log (counts only). */
    public enum Tier { FULL, SHORT, IDENTITY, NONE }

    private final Map<Long, EpcAttributes> byFull;
    private final Map<Long, EpcAttributes> byShort;
    private final Map<Long, EpcAttributes> byIdentity;
    private final int certificates;
    private final YearMonth latestLodgement;

    private EpcLookup(Map<Long, EpcAttributes> byFull, Map<Long, EpcAttributes> byShort,
                      Map<Long, EpcAttributes> byIdentity, int certificates, YearMonth latestLodgement) {
        this.byFull = byFull;
        this.byShort = byShort;
        this.byIdentity = byIdentity;
        this.certificates = certificates;
        this.latestLodgement = latestLodgement;
    }

    public record Match(Tier tier, EpcAttributes attributes) {}

    public Match find(PpdRow row) {
        String full = AddressKey.normalise(row.saon(), row.paon(), row.street());
        EpcAttributes hit = byFull.get(AddressKey.hash(row.postcode(), full));
        if (hit != null) {
            return new Match(Tier.FULL, hit);
        }
        hit = byShort.get(AddressKey.hash(row.postcode(), full));
        if (hit != null) {
            return new Match(Tier.SHORT, hit);
        }
        String identity = AddressKey.identity(row.saon(), row.paon(), row.street());
        if (!identity.isEmpty()) {
            hit = byIdentity.get(AddressKey.hash(row.postcode(), identity));
            if (hit != null) {
                return new Match(Tier.IDENTITY, hit);
            }
        }
        return new Match(Tier.NONE, null);
    }

    public int certificates() {
        return certificates;
    }

    public YearMonth latestLodgement() {
        return latestLodgement;
    }

    /** Reads the CSV (header row with upper-case names, as tools/epc_filter.py writes it). */
    public static EpcLookup load(Path csv) throws IOException {
        try (BufferedReader in = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            return load(in);
        }
    }

    static EpcLookup load(BufferedReader in) throws IOException {
        Map<String, Integer> col = header(in.readLine());
        Map<Long, EpcAttributes> byFull = new HashMap<>();
        Map<Long, EpcAttributes> byShort = new HashMap<>();
        Map<Long, EpcAttributes> byIdentity = new HashMap<>();
        Map<Long, Long> identityOwner = new HashMap<>();   // identity hash -> the one full-address hash that has it
        Set<Long> ambiguous = new HashSet<>();
        int certificates = 0;
        YearMonth latest = null;

        String line;
        while ((line = in.readLine()) != null) {
            List<String> f = CsvLine.split(line);
            if (f.size() < col.size()) {
                continue;
            }
            EpcAttributes attrs = attributes(f, col);
            if (attrs == null) {
                continue;
            }
            certificates++;
            YearMonth month = YearMonth.from(attrs.lodgementDate());
            latest = latest == null || month.isAfter(latest) ? month : latest;

            // the restricted fields: read, hashed, and dropped with this loop iteration
            String postcode = f.get(col.get("POSTCODE"));
            String a1 = f.get(col.get("ADDRESS1")), a2 = f.get(col.get("ADDRESS2")), a3 = f.get(col.get("ADDRESS3"));
            long fullHash = AddressKey.hash(postcode, AddressKey.normalise(a1, a2, a3));
            keepLatest(byFull, fullHash, attrs);
            keepLatest(byShort, AddressKey.hash(postcode, AddressKey.normalise(a1, a2)), attrs);
            String identity = AddressKey.identity(a1, a2, a3);
            if (!identity.isEmpty()) {
                long idHash = AddressKey.hash(postcode, identity);
                Long owner = identityOwner.putIfAbsent(idHash, fullHash);
                if (owner != null && owner != fullHash) {
                    ambiguous.add(idHash);      // two different addresses share these numbers: never guess
                }
                keepLatest(byIdentity, idHash, attrs);
            }
        }
        byIdentity.keySet().removeAll(ambiguous);
        return new EpcLookup(byFull, byShort, byIdentity, certificates, latest);
    }

    private static void keepLatest(Map<Long, EpcAttributes> map, long key, EpcAttributes attrs) {
        // merge = "put, or combine with what is there": keep whichever certificate was lodged later
        map.merge(key, attrs, (old, candidate) -> candidate.isNewerThan(old) ? candidate : old);
    }

    private static Map<String, Integer> header(String line) throws IOException {
        if (line == null) {
            throw new IOException("EPC file is empty");
        }
        Map<String, Integer> col = new HashMap<>();
        List<String> names = CsvLine.split(line);
        for (int i = 0; i < names.size(); i++) {
            col.put(names.get(i).trim().toUpperCase(), i);
        }
        for (String required : List.of("ADDRESS1", "ADDRESS2", "ADDRESS3", "POSTCODE", "PROPERTY_TYPE", "BUILT_FORM",
                "TOTAL_FLOOR_AREA", "NUMBER_HABITABLE_ROOMS", "CONSTRUCTION_AGE_BAND", "CURRENT_ENERGY_RATING",
                "LODGEMENT_DATE")) {
            if (!col.containsKey(required)) {
                throw new IOException("EPC file has no " + required + " column");
            }
        }
        return col;
    }

    /** The derived attributes of one certificate, or null when it has no usable lodgement date. */
    static EpcAttributes attributes(List<String> f, Map<String, Integer> col) {
        LocalDate lodged;
        try {
            lodged = LocalDate.parse(f.get(col.get("LODGEMENT_DATE")).trim());
        } catch (RuntimeException e) {
            return null;
        }
        return new EpcAttributes(
                positiveDouble(f.get(col.get("TOTAL_FLOOR_AREA"))),
                band(f.get(col.get("CURRENT_ENERGY_RATING"))),
                cleanBuiltForm(f.get(col.get("BUILT_FORM"))),
                cleanAgeBand(f.get(col.get("CONSTRUCTION_AGE_BAND"))),
                positiveInt(f.get(col.get("NUMBER_HABITABLE_ROOMS"))),
                blankToNull(f.get(col.get("PROPERTY_TYPE"))),
                lodged);
    }

    private static Double positiveDouble(String s) {
        try {
            double v = Double.parseDouble(s.trim());
            return v > 0 ? v : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer positiveInt(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            return v > 0 ? v : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String band(String s) {
        String t = s.trim().toUpperCase();
        return t.length() == 1 && t.charAt(0) >= 'A' && t.charAt(0) <= 'G' ? t : null;
    }

    private static String cleanBuiltForm(String s) {
        String t = s.trim();
        return t.isEmpty() || t.equalsIgnoreCase("Not Recorded") || t.startsWith("NO DATA") ? null : t;
    }

    /** "England and Wales: 1900-1929" -> "1900-1929"; "NO DATA!" / "INVALID!" -> null. */
    private static String cleanAgeBand(String s) {
        String t = s.trim().replaceFirst("^England and Wales: ", "");
        return t.isEmpty() || t.endsWith("!") ? null : t;
    }

    private static String blankToNull(String s) {
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
