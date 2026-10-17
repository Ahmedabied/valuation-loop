package uk.valuationloop.request;

import java.util.regex.Pattern;

/** UK postcode helpers. "sw11 1aa" -> "SW11 1AA"; outcode "SW11". */
public final class Postcodes {

    // outward code (area + district) then inward code (sector + unit); lenient on the exact letter rules
    private static final Pattern FULL = Pattern.compile("^([A-Z]{1,2}[0-9][A-Z0-9]?) ?([0-9][A-Z]{2})$");

    private Postcodes() {}

    public static String normalise(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("postcode is required");
        }
        String compact = raw.trim().toUpperCase().replaceAll("\\s+", "");
        if (compact.length() < 5 || compact.length() > 7) {
            throw new IllegalArgumentException("not a UK postcode: " + raw);
        }
        String candidate = compact.substring(0, compact.length() - 3) + " " + compact.substring(compact.length() - 3);
        if (!FULL.matcher(candidate).matches()) {
            throw new IllegalArgumentException("not a UK postcode: " + raw);
        }
        return candidate;
    }

    public static String outcode(String normalised) {
        return normalised.substring(0, normalised.indexOf(' '));
    }
}
