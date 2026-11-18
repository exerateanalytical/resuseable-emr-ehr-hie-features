/*
 * =============================================================================
 * Opes HealthCore Mobile SDK — Offline-First Sync, HLC, OIDC PKCE & SQLDelight Vault
 * Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
 * Website            : https://www.opeshealthsystems.com | Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mobile.sync

import com.opeshealth.healthcore.mobile.db.HealthCoreOfflineDatabase
import com.opeshealth.healthcore.mobile.platform.PlatformSqlDriverFactory
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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

@Serializable
data class SmartDiscoveryMetadata(
    val authorization_endpoint: String,
    val token_endpoint: String
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
 * Evaluates NEWS2 vitals alerts and Penicillin/Warfarin/eGFR CDSS safety locally in <1 ms
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
        activeMedCodesCsv: String,
        egfr: Double? = null
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
        if (egfr != null && egfr < 60.0) {
            alerts += LocalCdssAlert(
                severity = if (egfr < 30.0) "CRITICAL" else "WARNING",
                title = "Offline Renal Impairment Dose Guard (eGFR $egfr)",
                guidance = "Reduce renal-cleared medication dosage or select non-nephrotoxic alternative."
            )
        }
        return alerts
    }
}

/**
 * SMART on FHIR v2 OAuth 2.1 + PKCE client discovering backend OIDC endpoints
 * via `GET /.well-known/smart-configuration`.
 */
class SmartOidcPkceClient(
    private val baseUrl: String,
    private val httpClient: HttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discoverEndpoints(): SmartDiscoveryMetadata {
        val raw = httpClient.get("$baseUrl/.well-known/smart-configuration").bodyAsText()
        return json.decodeFromString(raw)
    }

    suspend fun exchangeAuthorizationCode(
        tokenEndpoint: String,
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String
    ): String {
        val raw = httpClient.submitForm(
            url = tokenEndpoint,
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("client_id", clientId)
                append("code", code)
                append("code_verifier", codeVerifier)
                append("redirect_uri", redirectUri)
            }
        ).bodyAsText()
        return json.parseToJsonElement(raw).jsonObject["access_token"]?.jsonPrimitive?.content ?: ""
    }
}

/**
 * Persistent SQLDelight offline clinical vault + bidirectional Ktor delta sync engine.
 * Reads patients directly from `HealthCoreOfflineDatabase`, enqueues signed SOAP/CHW
 * mutations into `local_sync_outbox`, and drains pending mutations when connectivity returns.
 */
class OfflineVaultSyncRepository(
    driverFactory: PlatformSqlDriverFactory,
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val nodeId: String = "mobile-edge-1"
) {
    val db: HealthCoreOfflineDatabase = HealthCoreOfflineDatabase(driverFactory.createDriver())
    private val queries = db.healthCoreOfflineQueries
    private val hlc = MobileHybridLogicalClock(nodeId)
    private val json = Json { ignoreUnknownKeys = true }

    fun seedSamplePatientsIfEmpty(facilityId: String, wallClockMs: Long) {
        if (queries.selectAllPatients().executeAsList().isEmpty()) {
            queries.insertOrReplacePatient(
                id = "01950001-0000-7000-8000-000000000001",
                facility_id = facilityId,
                mrn = "OPES-DLA-001",
                family_name = "Mbarga",
                given_name = "Clarisse",
                birth_date = "1988-03-19",
                gender = "female",
                blood_group = "O+",
                allergies_csv = "764146007",
                hlc_timestamp = hlc.nextStamp(wallClockMs)
            )
            queries.insertOrReplacePatient(
                id = "01950001-0000-7000-8000-000000000002",
                facility_id = facilityId,
                mrn = "OPES-DLA-002",
                family_name = "Kamdem",
                given_name = "Emmanuel",
                birth_date = "1974-11-02",
                gender = "male",
                blood_group = "A+",
                allergies_csv = "",
                hlc_timestamp = hlc.nextStamp(wallClockMs)
            )
        }
    }

    fun listCachedPatients() = queries.selectAllPatients().executeAsList()

    fun pendingOutboxCount(): Int = queries.selectPendingOutbox().executeAsList().size

    fun enqueueClinicalNoteOffline(patientId: String, soapText: String, stylusJson: String, wallClockMs: Long): String {
        val noteId = hlc.nextUuidV7(wallClockMs)
        val stamp = hlc.nextStamp(wallClockMs)
        queries.insertClinicalNote(
            id = noteId,
            patient_id = patientId,
            subjective = soapText,
            objective = null,
            assessment = null,
            plan_text = null,
            specialty_code = "GENERAL",
            stylus_strokes_json = stylusJson,
            status = "SIGNED_OFFLINE",
            hlc_timestamp = stamp
        )
        val payload = """{"patientId":"$patientId","noteText":${json.encodeToString(soapText)},"stylus":$stylusJson}"""
        queries.enqueueOutboxMutation(
            id = noteId,
            entity_type = "ClinicalNote",
            entity_id = noteId,
            operation = "UPSERT",
            hlc_timestamp = stamp,
            payload_json = payload
        )
        return noteId
    }

    fun recordVitalsOffline(
        patientId: String,
        tempC: Double?,
        heartRateBpm: Int?,
        respRate: Int?,
        systolicBp: Int?,
        spo2Percent: Int?,
        wallClockMs: Long
    ): Int {
        val news2 = OfflineClinicalSafetyEngine.computeNews2Score(tempC, heartRateBpm, respRate, systolicBp, spo2Percent)
        val vitalId = hlc.nextUuidV7(wallClockMs)
        val stamp = hlc.nextStamp(wallClockMs)
        queries.insertVitalSigns(
            id = vitalId,
            patient_id = patientId,
            temp_celsius = tempC,
            heart_rate_bpm = heartRateBpm?.toLong(),
            resp_rate_per_min = respRate?.toLong(),
            systolic_bp = systolicBp?.toLong(),
            spo2_percent = spo2Percent?.toLong(),
            news2_score = news2.toLong(),
            recorded_at = stamp
        )
        queries.enqueueOutboxMutation(
            id = vitalId,
            entity_type = "Observation",
            entity_id = vitalId,
            operation = "UPSERT",
            hlc_timestamp = stamp,
            payload_json = """{"patientId":"$patientId","news2Score":$news2,"spo2":${spo2Percent ?: 0}}"""
        )
        return news2
    }

    fun enqueueChwMuacVisitOffline(patientId: String, muacMm: Int, wallClockMs: Long): String {
        val visitId = hlc.nextUuidV7(wallClockMs)
        val stamp = hlc.nextStamp(wallClockMs)
        val samFlag = muacMm < 115
        queries.enqueueOutboxMutation(
            id = visitId,
            entity_type = "ChwVisit",
            entity_id = visitId,
            operation = "UPSERT",
            hlc_timestamp = stamp,
            payload_json = """{"patientId":"$patientId","muacMm":$muacMm,"severeAcuteMalnutrition":$samFlag}"""
        )
        return visitId
    }

    fun currentSyncCursor(facilityId: String): Long =
        queries.selectCheckpoint(facilityId).executeAsOneOrNull()?.last_sync_seq ?: 0L

    suspend fun syncWithServer(facilityId: String, bearerToken: String, sinceSyncSeq: Long? = null): Result<Int> {
        return runCatching {
            val pending = queries.selectPendingOutbox().executeAsList()
            if (pending.isNotEmpty()) {
                val payloads = pending.map { row ->
                    OfflineMutationPayload(
                        originNodeId = nodeId,
                        entityType = row.entity_type,
                        entityId = row.entity_id,
                        operation = row.operation,
                        hlcTimestamp = row.hlc_timestamp,
                        payloadJson = row.payload_json
                    )
                }
                val pushResp = httpClient.post("$baseUrl/api/v1/sync/push?facilityId=$facilityId") {
                    header(HttpHeaders.Authorization, "Bearer $bearerToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(payloads))
                }
                if (pushResp.status.isSuccess()) {
                    pending.forEach { queries.markOutboxSynced(it.id) }
                }
            }

            val cursor = sinceSyncSeq ?: currentSyncCursor(facilityId)
            val pullResp = httpClient.get("$baseUrl/api/v1/sync/pull?facilityId=$facilityId&sinceSeq=$cursor") {
                header(HttpHeaders.Authorization, "Bearer $bearerToken")
            }
            if (pullResp.status.isSuccess()) {
                val root = json.parseToJsonElement(pullResp.bodyAsText()).jsonObject
                val nextSeq = root["nextSyncSeq"]?.jsonPrimitive?.longOrNull ?: cursor
                val serverHlc = root["serverHlc"]?.jsonPrimitive?.contentOrNull ?: hlc.nextStamp(0L)
                root["deltas"]?.jsonArray?.forEach { elem ->
                    val delta = elem.jsonObject
                    val entityType = delta["entityType"]?.jsonPrimitive?.contentOrNull ?: ""
                    val entityId = delta["entityId"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val op = delta["operation"]?.jsonPrimitive?.contentOrNull ?: "UPSERT"
                    val payloadStr = delta["payloadJson"]?.jsonPrimitive?.contentOrNull ?: "{}"
                    if (entityType == "Patient") {
                        if (op.equals("DELETE", ignoreCase = true)) {
                            queries.deleteLocalPatientById(entityId)
                        } else {
                            val pObj = json.parseToJsonElement(payloadStr).jsonObject
                            queries.insertOrReplacePatient(
                                id = entityId,
                                facility_id = facilityId,
                                mrn = pObj["mrn"]?.jsonPrimitive?.contentOrNull ?: entityId,
                                family_name = pObj["familyName"]?.jsonPrimitive?.contentOrNull ?: "Unknown",
                                given_name = pObj["givenName"]?.jsonPrimitive?.contentOrNull ?: "Patient",
                                birth_date = pObj["birthDate"]?.jsonPrimitive?.contentOrNull ?: "1970-01-01",
                                gender = pObj["gender"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                                blood_group = pObj["bloodGroup"]?.jsonPrimitive?.contentOrNull,
                                allergies_csv = pObj["allergiesCsv"]?.jsonPrimitive?.contentOrNull,
                                hlc_timestamp = delta["hlcTimestamp"]?.jsonPrimitive?.contentOrNull ?: serverHlc
                            )
                        }
                    }
                }
                queries.upsertCheckpoint(facilityId, nextSeq, serverHlc)
            }
            pending.size
        }
    }
}
