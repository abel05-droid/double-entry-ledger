package com.abel.ledger.observability;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Generates RFC 9562 UUID version 7 values: a 48-bit big-endian Unix
 * millisecond timestamp, a 4-bit version, a 12-bit random field, a 2-bit
 * variant, and a 62-bit random field. Unlike {@link UUID#randomUUID()}
 * (version 4, fully random), UUIDv7 is time-ordered — sorting a set of
 * UUIDv7 values sorts them by creation time — which is the property that
 * makes it a good default for correlation IDs: it doubles as a coarse
 * timestamp and sorts naturally in log storage without needing a separate
 * indexed timestamp column.
 *
 * <p>{@code java.util.UUID} has no built-in v7 generator as of Java 21, so
 * this hand-assembles the 128 bits directly per the RFC's layout:
 *
 * <pre>
 * most significant 64 bits:  [48-bit unix_ts_ms][4-bit version=0111][12-bit rand_a]
 * least significant 64 bits: [2-bit variant=10][62-bit rand_b]
 * </pre>
 */
public final class Uuid7Generator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuid7Generator() {
    }

    public static UUID generate() {
        long timestampMs = Instant.now().toEpochMilli();
        long randomA = RANDOM.nextLong();
        long randomB = RANDOM.nextLong();

        long mostSigBits = ((timestampMs & 0xFFFFFFFFFFFFL) << 16)
                | (0x7L << 12)
                | (randomA & 0x0FFFL);

        long leastSigBits = (randomB & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;

        return new UUID(mostSigBits, leastSigBits);
    }
}
