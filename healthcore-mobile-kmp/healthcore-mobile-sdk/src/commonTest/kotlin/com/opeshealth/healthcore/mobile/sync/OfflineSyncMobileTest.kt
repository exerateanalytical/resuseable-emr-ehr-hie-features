/*
 * =============================================================================
 * Opes HealthCore Mobile SDK — Offline Sync HLC & Local Safety Tests (Phase 6)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mobile.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineSyncMobileTest {

    @Test
    fun hybridLogicalClockRemainsStrictlyMonotonicDespiteClockRegression() {
        val hlc = MobileHybridLogicalClock("android-field-01")
        val t1 = hlc.nextStamp(1_732_000_000_000L)
        val t2 = hlc.nextStamp(1_732_000_000_000L)
        val t3 = hlc.nextStamp(1_731_999_990_000L) // Wall clock jumped backward offline

        assertTrue(t2 > t1, "Second stamp at same millisecond must increment counter")
        assertTrue(t3 > t2, "Clock regression must still produce strictly greater HLC stamp")
    }

    @Test
    fun generatesValidOfflineUuidV7Format() {
        val hlc = MobileHybridLogicalClock("ipad-ward-04")
        val id1 = hlc.nextUuidV7(1_732_000_001_000L)
        val id2 = hlc.nextUuidV7(1_732_000_002_000L)

        assertEquals(36, id1.length)
        assertEquals(36, id2.length)
        assertTrue(id2 > id1, "UUIDv7 identifiers must sort chronologically")
    }
}
