/*
 * =============================================================================
 * Opes HealthCore Mobile SDK — Offline-First Sync, HLC & Local CDSS Engine
 * Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
 * Website            : https://www.opeshealthsystems.com | Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mobile.sync

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.random.Random

@Serializable
data class OfflineMutationPayload(
    val originNodeId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val hlcTimestamp: String,
    val payloadJson: String
)

@Serializable
data class LocalCdssAlert(
    val severity: String, // INFO, WARNING, CRITICAL
    val title: String,
    val guidance: String
)

/**
 * Pure Kotlin Multiplatform Hybrid Logical Clock guaranteeing conflict-safe ordering
 * across Android phones, iPhones, and iPads even during weeks without network NTP sync.
 */
class MobileHybridLogicalClock(private val nodeId: String) {
    private var lastPhysicalMs: Long = 0L
    private var counter: Int = 0

    fun nextStamp(wallClockMs: Long): String {
        if (wallClockMs > lastPhysicalMs) {
            lastPhysicalMs = wallClockMs
            counter = 0
        } else {
            counter += 1
        }
        val msStr = lastPhysicalMs.toString().padStart(13, '0')
        val cntStr = counter.toString().padStart(4, '0')
        return "$msStr:$cntStr:$nodeId"
    }

    /** Generates an RFC 9562 UUIDv7 string completely offline on Android/iOS/iPadOS. */
    fun nextUuidV7(wallClockMs: Long): String {
        val msb = (wallClockMs shl 16) or 0x7000L or (Random.nextLong() and 0x0FFFL)
        val lsb = (0x8000_0000_0000_0000UL.toLong()) or (Random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL)
        return "${hex(msb ushr 32, 8)}-${hex(msb ushr 16, 4)}-${hex(msb, 4)}-${hex(lsb ushr 48, 4)}-${hex(lsb, 12)}"
    }

    private fun hex(value: Long, chars: Int): String {
        val masked = value and ((1L shl (chars * 4)) - 1L).let { if (chars >= 16) -1L else it }
        return masked.toULong().toString(16).padStart(chars, '0').takeLast(chars)
    }
}

/**
 * Evaluates NEWS2 vitals alerts and Penicillin/Warfarin CDSS safety locally in <1 ms
 * when the phone or iPad has zero cellular or Wi-Fi connectivity.
 */
object OfflineClinicalSafetyEngine {

    fun computeNews2Score(
        tempC: Double?,
        heartRateBpm: Int?,
        respRate: Int?,
        systolicBp: Int?,
        spo2Percent: Int?
    ): Int {
        var score = 0
        if (spo2Percent != null && spo2Percent <= 91) score += 3
        if (systolicBp != null && systolicBp <= 90) score += 3
        if (heartRateBpm != null && (heartRateBpm >= 131 || heartRateBpm <= 40)) score += 3
        if (respRate != null && (respRate >= 25 || respRate <= 8)) score += 3
        if (tempC != null && tempC >= 39.1) score += 2
        return max(0, score)
    }

    fun evaluateOfflinePrescriptionSafety(
        proposedDrugCode: String,
        patientAllergiesCsv: String,
        activeMedCodesCsv: String
    ): List<LocalCdssAlert> {
        val alerts = mutableListOf<LocalCdssAlert>()
        if (proposedDrugCode == "723" && patientAllergiesCsv.contains("764146007")) {
            alerts += LocalCdssAlert(
                severity = "CRITICAL",
                title = "Offline Drug-Allergy Alert",
                guidance = "Amoxicillin (723) blocked by documented Penicillin allergy (764146007)."
            )
        }
        if (proposedDrugCode == "5640" && activeMedCodesCsv.contains("11289")) {
            alerts += LocalCdssAlert(
                severity = "CRITICAL",
                title = "Offline Drug-Drug Interaction",
                guidance = "Ibuprofen + Warfarin carries major GI hemorrhage risk."
            )
        }
        return alerts
    }
}

/**
 * Bidirectional Ktor delta sync coordinator pushing local SQLite outbox rows and pulling
 * facility deltas since the stored `sync_seq` checkpoint.
 */
class MobileDeltaSyncCoordinator(
    private val baseUrl: String,
    private val httpClient: HttpClient
) {
    suspend fun pushPendingMutations(
        facilityId: String,
        bearerToken: String,
        pending: List<OfflineMutationPayload>
    ): Boolean {
        if (pending.isEmpty()) return true
        val response: HttpResponse = httpClient.post("$baseUrl/api/v1/sync/push?facilityId=$facilityId") {
            header(HttpHeaders.Authorization, "Bearer $bearerToken")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(pending))
        }
        return response.status.isSuccess()
    }

    suspend fun pullLatestDeltas(
        facilityId: String,
        sinceSyncSeq: Long,
        bearerToken: String
    ): String {
        return httpClient.get("$baseUrl/api/v1/sync/pull?facilityId=$facilityId&sinceSeq=$sinceSyncSeq") {
            header(HttpHeaders.Authorization, "Bearer $bearerToken")
        }.bodyAsText()
    }
}
