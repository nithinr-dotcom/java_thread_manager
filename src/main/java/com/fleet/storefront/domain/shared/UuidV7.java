package com.fleet.storefront.domain.shared;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * RFC 9562 UUID version 7: 48-bit Unix-millisecond timestamp followed by 74 random bits.
 *
 * <p>Chosen for entity identity because it can be generated offline on any of thousands of edge
 * nodes without coordination (no collisions at Central HQ), while still being roughly time-ordered,
 * which keeps B-tree index inserts append-mostly.
 */
public final class UuidV7 {

    private static final RandomGenerator RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID next() {
        return at(Instant.now(), RANDOM);
    }

    public static UUID at(Instant instant, RandomGenerator random) {
        long millis = instant.toEpochMilli();
        long randA = random.nextLong() & 0x0FFFL;
        long randB = random.nextLong();

        long msb = (millis << 16) | (0x7L << 12) | randA;
        long lsb = (randB & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // IETF variant (10xx)
        return new UUID(msb, lsb);
    }

    public static Instant timestampOf(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("Not a UUIDv7: " + uuid);
        }
        return Instant.ofEpochMilli(uuid.getMostSignificantBits() >>> 16);
    }
}
