package com.masspos.common.persistence;

import java.security.SecureRandom;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * RFC 9562 UUID version 7: {@code 48-bit Unix ms | ver 7 | 12-bit counter | var 10 | 62 random bits}.
 *
 * <p>Ids are time-ordered, so inserts append to the right edge of SQLite and PostgreSQL B-trees, and
 * they are collision-free across terminals without any coordination, which offline multi-terminal
 * sync depends on. Within one millisecond the counter keeps ids strictly increasing (RFC 9562
 * §6.2, method 1). If the wall clock steps backwards (an NTP correction after a long offline spell)
 * the last timestamp is reused, so ordering on this terminal never regresses.
 */
public final class UuidV7 {

    private static final UuidV7 DEFAULT = new UuidV7(System::currentTimeMillis, new SecureRandom());

    private static final long VERSION_BITS = 0x7000L;
    private static final long VARIANT_BITS = 0x8000_0000_0000_0000L;
    private static final long RANDOM_62_MASK = 0x3FFF_FFFF_FFFF_FFFFL;
    private static final int MAX_COUNTER = 0xFFF;
    /** Seeding the counter in its lower half leaves at least 2048 increments per millisecond. */
    private static final int COUNTER_SEED_BOUND = 1 << 11;

    private final LongSupplier clock;
    private final Random random;
    // Not synchronized: on Java 21 that would pin virtual threads to their carrier.
    private final ReentrantLock lock = new ReentrantLock();
    private long lastMillis = -1;
    private int counter;

    UuidV7(LongSupplier clock, Random random) {
        this.clock = clock;
        this.random = random;
    }

    public static UUID next() {
        return DEFAULT.generate();
    }

    /** Unix epoch milliseconds embedded in a version 7 UUID. */
    public static long timestampMillis(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("Not a UUIDv7: " + uuid);
        }
        return uuid.getMostSignificantBits() >>> 16;
    }

    UUID generate() {
        long millis;
        int sequence;
        lock.lock();
        try {
            long now = clock.getAsLong();
            if (now > lastMillis) {
                lastMillis = now;
                counter = random.nextInt(COUNTER_SEED_BOUND);
            } else if (++counter > MAX_COUNTER) {
                // Counter exhausted within one ms, or the clock stalled/regressed: borrow the next ms.
                lastMillis++;
                counter = random.nextInt(COUNTER_SEED_BOUND);
            }
            millis = lastMillis;
            sequence = counter;
        } finally {
            lock.unlock();
        }
        long msb = (millis << 16) | VERSION_BITS | sequence;
        long lsb = (random.nextLong() & RANDOM_62_MASK) | VARIANT_BITS;
        return new UUID(msb, lsb);
    }
}
