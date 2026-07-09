package com.abel.ledger.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Uuid7GeneratorTest {

    @Test
    void generatesAVersion7VariantRfc4122Uuid() {
        UUID uuid = Uuid7Generator.generate();

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    @Test
    void generatesUniqueValues() {
        Set<UUID> generated = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertThat(generated.add(Uuid7Generator.generate())).as("must not generate a duplicate").isTrue();
        }
    }

    @Test
    void embedsANonDecreasingMillisecondTimestamp() throws InterruptedException {
        // The embedded timestamp only guarantees ordering at millisecond
        // granularity — two UUIDs generated within the same millisecond
        // are NOT guaranteed to sort by their random remainder — so this
        // asserts on the extracted timestamp field directly rather than
        // on full UUID.compareTo() ordering, with a small sleep to force
        // distinct milliseconds.
        long first = extractTimestampMs(Uuid7Generator.generate());
        Thread.sleep(5);
        long second = extractTimestampMs(Uuid7Generator.generate());

        assertThat(second).isGreaterThan(first);
    }

    private static long extractTimestampMs(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }
}
