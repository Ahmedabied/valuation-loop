package uk.valuationloop.outbox;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Time-ordered UUIDs (version 7): 48 bits of Unix milliseconds, a 12-bit counter, then random bits.
 * Sorting by id is sorting by creation time, which keeps the outbox and the database indexes friendly.
 *
 * The 12 bits after the version are a counter, not random (RFC 9562 §6.2, method 1): it starts at a random value in
 * the lower half each new millisecond and goes up by one for every id in the same millisecond, so ids made one after
 * another in one JVM always sort in the order they were made. The outbox relay relies on that to keep events written
 * in the same millisecond in write order. If the counter runs out (4,096 ids in one millisecond) or the clock steps
 * back, the timestamp is moved forward by one instead, so ids never go backwards.
 * Java 21 has no built-in v7 generator, so this is the whole thing in a few lines.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long MAX_COUNTER = 0x0FFFL;

    private static long lastMillis = -1;   // guarded by the class lock
    private static long counter;

    private UuidV7() {}

    public static UUID next() {
        long millis;
        long seq;
        synchronized (UuidV7.class) {
            long now = System.currentTimeMillis();
            if (now > lastMillis) {
                lastMillis = now;
                counter = RANDOM.nextInt(0x0800);              // random start, lower half: room to count up
            } else if (counter < MAX_COUNTER) {
                counter++;                                     // same millisecond (or the clock stepped back)
            } else {
                lastMillis++;                                  // counter exhausted: borrow the next millisecond
                counter = 0;
            }
            millis = lastMillis;
            seq = counter;
        }
        long msb = (millis << 16) | 0x7000L | seq;            // timestamp | version 7 | counter
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // variant 10xx
        return new UUID(msb, lsb);
    }

    /** Short prefixed ids for aggregates, e.g. vr_018f6a3e-..., ld_018f6a3e-... */
    public static String prefixed(String prefix) {
        return prefix + "_" + next();
    }
}
