/*
 * =============================================================================
 * Opes HealthCore SDK — Sequential UUIDv7 Generator (RFC 9562)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.id;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Generates monotonically time-ordered RFC 9562 UUIDv7 identifiers.
 *
 * <p><b>Why UUIDv7 instead of random UUIDv4?</b></p>
 * <ul>
 *   <li><b>Windows SQL Server (MSSQL) &amp; MySQL InnoDB speed:</b> Random UUIDv4 primary keys
 *       cause severe B-tree clustered-index page splits and random disk I/O at scale.
 *       UUIDv7 embeds a 48-bit millisecond Unix timestamp in the most significant bits,
 *       keeping inserts append-sequential across PostgreSQL, MSSQL, and MySQL.</li>
 *   <li><b>Offline-First Client Minting:</b> Disconnected phones, iPads, and edge clinics
 *       can safely mint globally unique primary keys offline with zero central coordination.</li>
 * </ul>
 */
public final class UuidV7Generator {

    /** Cryptographically strong PRNG seeded once for the 74 random bits per UUIDv7. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Last timestamp seen by this JVM node to guarantee strict monotonicity within the same ms. */
    private static long lastTimestampMs = -1L;

    /** 12-bit sub-millisecond counter incremented when multiple IDs are generated in 1 ms. */
    private static int subMsSequence = 0;

    private UuidV7Generator() {
        // Utility class; prevent instantiation.
    }

    /**
     * Generates the next strictly monotonic UUIDv7 identifier.
     *
     * <p>Thread-safe via short synchronized block so batch imports and virtual-thread bursts
     * never emit colliding sub-millisecond sequences.</p>
     *
     * @return newly minted RFC 9562 UUIDv7
     */
    public static synchronized UUID nextUuid() {
        long nowMs = Instant.now().toEpochMilli();

        if (nowMs == lastTimestampMs) {
            // Same millisecond: advance 12-bit sequence counter (0..4095).
            subMsSequence = (subMsSequence + 1) & 0x0FFF;
            if (subMsSequence == 0) {
                // Sequence wrapped in <1ms; spin briefly until next millisecond tick.
                while (nowMs <= lastTimestampMs) {
                    nowMs = Instant.now().toEpochMilli();
                }
                lastTimestampMs = nowMs;
            }
        } else if (nowMs > lastTimestampMs) {
            lastTimestampMs = nowMs;
            subMsSequence = RANDOM.nextInt(256); // Small random seed avoids predictable low bits.
        } else {
            // Clock stepped backward (e.g. NTP skew): clamp to lastTimestampMs to preserve order.
            subMsSequence = (subMsSequence + 1) & 0x0FFF;
        }

        // Most significant 64 bits: [48-bit timestamp][4-bit version=7][12-bit subMsSequence]
        long msb = (lastTimestampMs & 0xFFFFFFFFFFFFL) << 16;
        msb |= 0x7000L; // Version 7 nibble
        msb |= (subMsSequence & 0x0FFFL);

        // Least significant 64 bits: [2-bit RFC 4122 variant=10][62-bit secure random]
        long lsb = RANDOM.nextLong();
        lsb &= 0x3FFFFFFFFFFFFFFFL;
        lsb |= 0x8000000000000000L; // Variant 10xx

        return new UUID(msb, lsb);
    }
}
