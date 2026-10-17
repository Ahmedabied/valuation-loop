package uk.valuationloop.outbox;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Time-ordered UUIDs (version 7): 48 bits of Unix milliseconds, then random bits.
 * Sorting by id is sorting by creation time, which keeps the outbox and the database indexes friendly.
 * Java 21 has no built-in v7 generator, so this is the whole thing in a dozen lines.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static UUID next() {
        long millis = System.currentTimeMillis();
        long randA = RANDOM.nextLong() & 0x0FFFL;            // 12 random bits
        long msb = (millis << 16) | 0x7000L | randA;          // timestamp | version 7 | rand_a
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // variant 10xx
        return new UUID(msb, lsb);
    }

    /** Short prefixed ids for aggregates, e.g. vr_018f6a3e-..., ld_018f6a3e-... */
    public static String prefixed(String prefix) {
        return prefix + "_" + next();
    }
}
