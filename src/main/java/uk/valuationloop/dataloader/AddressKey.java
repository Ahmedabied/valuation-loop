package uk.valuationloop.dataloader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Normalised address keys for the PPD <-> EPC join, which happens only inside the loader's memory.
 *
 * The two datasets spell the same address differently: PPD splits it into SAON ("FLAT 3"), PAON ("12") and street
 * ("EXAMPLE ROAD"); EPC has free-text ADDRESS1-3 ("Flat 3, 12 Example Road"). So both sides become a bag of words:
 * uppercase, punctuation to spaces, split, drop filler words (FLAT, APARTMENT, ...), sort. Sorting makes the key
 * order-insensitive: "Flat 3, 12 Example Road" and "12 EXAMPLE ROAD" + "FLAT 3" give the same key.
 *
 * {@link #identity} is a looser key for the fallback match: only the tokens that identify a dwelling within a
 * postcode (anything with a digit, plus single letters such as the B in "Flat B").
 *
 * Licence rule: EPC address fields are OS / Royal Mail restricted. They are turned into a 64-bit {@link #hash}
 * as soon as they are read, and only the hash is kept; no address string is stored, indexed, logged or committed.
 */
public final class AddressKey {

    private static final Set<String> FILLER = Set.of("FLAT", "APARTMENT", "APT", "UNIT", "THE");

    private AddressKey() {}

    /** "Flat 3, 12 Example Road" -> "12 3 EXAMPLE ROAD" (sorted tokens, filler dropped). Nulls and blanks are ignored. */
    public static String normalise(String... parts) {
        return Arrays.stream(tokens(parts)).filter(t -> !FILLER.contains(t)).sorted().collect(Collectors.joining(" "));
    }

    /** "Flat B, 12 Example Road" -> "12 B": the dwelling-identifying tokens only, sorted; "" when there are none. */
    public static String identity(String... parts) {
        return Arrays.stream(tokens(parts)).filter(AddressKey::identifies).sorted().collect(Collectors.joining(" "));
    }

    /** "sw11 1aa" -> "SW111AA": the postcode part of a key, spacing removed. */
    public static String postcode(String raw) {
        return raw == null ? "" : raw.toUpperCase().replaceAll("\\s+", "");
    }

    /** First 8 bytes of SHA-256 over "postcode|key": collisions are negligible at 400k keys, and it is one long. */
    public static long hash(String postcode, String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((postcode(postcode) + "|" + key).getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (d[i] & 0xFF);   // & 0xFF: Java bytes are signed, this reads them as 0..255
            }
            return h;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }

    private static String[] tokens(String... parts) {
        String joined = Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).collect(Collectors.joining(" "));
        String cleaned = joined.toUpperCase().replaceAll("[^A-Z0-9]+", " ").trim();
        return cleaned.isEmpty() ? new String[0] : cleaned.split(" ");
    }

    private static boolean identifies(String token) {
        return token.length() == 1 && Character.isLetter(token.charAt(0)) || token.chars().anyMatch(Character::isDigit);
    }
}
