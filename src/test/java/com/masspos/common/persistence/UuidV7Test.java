package com.masspos.common.persistence;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UuidV7Test {

    @Test
    void layoutFollowsRfc9562() {
        UUID id = new UuidV7(() -> 1_757_548_800_000L, new Random(42)).generate();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(UuidV7.timestampMillis(id)).isEqualTo(1_757_548_800_000L);
    }

    @Test
    void textFormIsStrictlyIncreasingWithinOneMillisecondEvenPastTheCounter() {
        UuidV7 generator = new UuidV7(() -> 1_000L, new Random(1));

        String previous = generator.generate().toString();
        for (int i = 0; i < 10_000; i++) {
            String next = generator.generate().toString();
            assertThat(next.compareTo(previous)).isPositive();
            previous = next;
        }
        // 10k ids cannot fit the 12-bit counter of one millisecond: later ms were borrowed.
        assertThat(UuidV7.timestampMillis(UUID.fromString(previous))).isGreaterThan(1_000L);
    }

    @Test
    void clockSteppingBackwardsDoesNotBreakOrdering() {
        long[] now = {5_000L};
        UuidV7 generator = new UuidV7(() -> now[0], new Random(7));

        UUID before = generator.generate();
        now[0] = 4_000L;
        UUID after = generator.generate();

        assertThat(after.toString().compareTo(before.toString())).isPositive();
        assertThat(UuidV7.timestampMillis(after)).isEqualTo(5_000L);
    }

    @Test
    void defaultGeneratorIsCollisionFreeAcrossThreads() {
        Set<UUID> ids = ConcurrentHashMap.newKeySet();
        IntStream.range(0, 200_000).parallel().forEach(i -> ids.add(UuidV7.next()));

        assertThat(ids).hasSize(200_000);
    }

    @Test
    void rejectsOtherVersions() {
        assertThatThrownBy(() -> UuidV7.timestampMillis(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
