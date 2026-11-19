/*
 * =============================================================================
 * Opes HealthCore Mobile SDK — KMP Offline FHIR DataSyncManager (SQLDelight)
 * Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
 * Website            : https://www.opeshealthsystems.com | Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mobile.sync

import com.opeshealth.healthcore.mobile.db.HealthCoreOfflineDatabase
import com.opeshealth.healthcore.mobile.platform.PlatformSqlDriverFactory
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.min
import kotlin.math.pow

/**
 * Priority tiers for offline-generated HL7 FHIR R4 resources queued in SQLDelight.
 * Lower numeric value = higher priority when draining the background outbox.
 */
enum class FhirSyncPriority(val level: Long) {
    /** Acute emergency & clinical safety alerts (AllergyIntolerance, Consent BTG, STAT Observation vitals). */
    CRITICAL_SAFETY(10L),
    /** Core patient identity & active clinical encounters (Patient, Encounter, DiagnosticReport). */
    HIGH_CLINICAL(20L),
    /** Orders, prescriptions, immunizations & signed SOAP notes (MedicationRequest, ClinicalNote, Immunization, Condition). */
    STANDARD_CLINICAL(30L),
    /** Routine administrative, SDoH, PROM surveys & background telemetry. */
    ROUTINE_BACKGROUND(50L);

    companion object {
        fun forResourceType(resourceType: String): FhirSyncPriority = when (resourceType.trim()) {
            "AllergyIntolerance", "Consent", "Flag", "DetectedIssue" -> CRITICAL_SAFETY
            "Patient", "Encounter", "Observation", "DiagnosticReport" -> HIGH_CLINICAL
            "MedicationRequest", "MedicationAdministration", "ClinicalNote",
            "Condition", "Procedure", "Immunization", "ServiceRequest" -> STANDARD_CLINICAL
            else -> ROUTINE_BACKGROUND
        }
    }
}

/**
 * Conflict resolution strategies supported by [DataSyncManager] when an offline-generated
 * or offline-modified FHIR resource collides with a newer or concurrent server version.
 */
enum class FhirConflictResolutionStrategy {
    /**
     * Never silently overwrites high-risk clinical resources (AllergyIntolerance, MedicationRequest,
     * ClinicalNote, Consent, Condition); quarantines both payloads in `local_fhir_sync_conflict`
     * for explicit clinician adjudication.
     */
    CLINICAL_SAFETY_QUARANTINE,

    /**
     * Performs structural 3-way JSON field-level merge against the common ancestor (`basePayloadJson`),
     * merging non-overlapping field edits and set-unioning additive FHIR arrays (`identifier`, `telecom`,
     * `address`), while falling back to HLC or quarantine if the same clinical field was modified on both sides.
     */
    THREE_WAY_FIELD_MERGE,

    /**
     * Deterministically orders concurrent mutations using the Hybrid Logical Clock
     * (`<physicalMs>:<counter>:<nodeId>`) timestamp.
     */
    HLC_LAST_WRITE_WINS,

    /** Forces the local offline mobile version over the remote server version. */
    CLIENT_WINS,

    /** Retains the authoritative server FHIR resource and discards the conflicting local mutation. */
    SERVER_WINS
}

/**
 * Clinician or automated adjudication choice for resolving an `OPEN` conflict in SQLDelight.
 */
sealed class FhirConflictResolutionDecision {
    /** Promotes the local offline FHIR resource version and re-queues it with a bumped version/HLC. */
    data object AcceptLocalClient : FhirConflictResolutionDecision()

    /** Accepts the remote server FHIR resource version and marks the local conflict closed. */
    data object KeepRemoteServer : FhirConflictResolutionDecision()

    /** Applies a clinician-reviewed or 3-way merged FHIR JSON payload and queues it for push. */
    data class ApplyMergedFhirJson(val mergedFhirJson: String) : FhirConflictResolutionDecision()
}

/**
 * Lifecycle state of the [DataSyncManager] background engine.
 */
enum class SyncManagerLifecycleState {
    IDLE,
    DRAINING_QUEUE,
    PULLING_DELTAS,
    RESOLVING_CONFLICTS,
    OFFLINE_PAUSED,
    ERROR
}

@Serializable
data class LocalFhirResourceRecord(
    val resourceKey: String,
    val facilityId: String,
    val resourceType: String,
    val resourceId: String,
    val patientId: String?,
    val versionId: Long,
    val etag: String,
    val hlcTimestamp: String,
    val originNodeId: String,
    val baseFhirJson: String?,
    val fhirJson: String,
    val syncState: String,
    val updatedAtMs: Long
)

@Serializable
data class QueuedFhirSyncItem(
    val queueId: String,
    val facilityId: String,
    val resourceType: String,
    val resourceId: String,
    val patientId: String?,
    val operation: String,
    val priority: Long,
    val baseVersionId: Long,
    val baseEtag: String?,
    val hlcTimestamp: String,
    val originNodeId: String,
    val basePayloadJson: String?,
    val payloadJson: String,
    val status: String,
    val attemptCount: Long,
    val maxAttempts: Long,
    val nextAttemptAtMs: Long,
    val lastError: String?,
    val createdAtMs: Long
)

@Serializable
data class FhirSyncConflictRecord(
    val conflictId: String,
    val queueId: String,
    val facilityId: String,
    val resourceType: String,
    val resourceId: String,
    val patientId: String?,
    val conflictType: String,
    val resolutionStrategy: String,
    val localHlc: String,
    val remoteHlc: String,
    val localVersionId: Long,
    val remoteVersionId: Long,
    val basePayloadJson: String?,
    val localPayloadJson: String,
    val remotePayloadJson: String,
    val mergedPayloadJson: String?,
    val status: String,
    val resolvedBy: String?,
    val resolvedAtMs: Long?,
    val detectedAtMs: Long
)

@Serializable
data class SyncBatchExecutionSummary(
    val facilityId: String,
    val pushedCount: Int,
    val pulledCount: Int,
    val autoMergedCount: Int,
    val quarantinedConflictCount: Int,
    val retryScheduledCount: Int,
    val remainingQueueDepth: Int,
    val latestSyncSeq: Long,
    val completedAtHlc: String
)

@Serializable
data class SyncEngineTelemetry(
    val state: SyncManagerLifecycleState = SyncManagerLifecycleState.IDLE,
    val queuedCount: Int = 0,
    val openConflictCount: Int = 0,
    val lastSyncedSeq: Long = 0L,
    val lastSyncWallClockMs: Long = 0L,
    val lastErrorMessage: String? = null
)

@Serializable
data class OfflineMediaAttachmentRecord(
    val mediaId: String,
    val facilityId: String,
    val patientId: String,
    val fileName: String,
    val contentType: String,
    val clinicalCategory: String,
    val localEncryptedPath: String,
    val totalSizeBytes: Long,
    val totalChunks: Long,
    val uploadedChunks: Long = 0L,
    val remoteUploadId: String? = null,
    val sha256Hex: String,
    val status: String = "QUEUED",
    val createdAtMs: Long
)

@Serializable
data class OfflineMediaSyncSummary(
    val facilityId: String,
    val completedAttachments: Int,
    val uploadedChunksCount: Int,
    val failedAttachments: Int
)

/**
 * SQLDelight persistence adapter encapsulating all local database operations required by
 * [DataSyncManager], backed by `HealthCoreOffline.sq` (`HealthCoreOfflineDatabase`).
 */
class SqlDelightFhirSyncStore(val db: HealthCoreOfflineDatabase) {
    private val queries = db.healthCoreOfflineQueries

    fun upsertLocalResource(record: LocalFhirResourceRecord) {
        queries.upsertLocalFhirResource(
            resource_key = record.resourceKey,
            facility_id = record.facilityId,
            resource_type = record.resourceType,
            resource_id = record.resourceId,
            patient_id = record.patientId,
            version_id = record.versionId,
            etag = record.etag,
            hlc_timestamp = record.hlcTimestamp,
            origin_node_id = record.originNodeId,
            base_fhir_json = record.baseFhirJson,
            fhir_json = record.fhirJson,
            sync_state = record.syncState,
            updated_at_ms = record.updatedAtMs
        )
    }

    fun getLocalResource(resourceType: String, resourceId: String): LocalFhirResourceRecord? {
        val key = "$resourceType/$resourceId"
        return queries.selectLocalFhirResourceByKey(key).executeAsOneOrNull()?.let { row ->
            LocalFhirResourceRecord(
                resourceKey = row.resource_key,
                facilityId = row.facility_id,
                resourceType = row.resource_type,
                resourceId = row.resource_id,
                patientId = row.patient_id,
                versionId = row.version_id,
                etag = row.etag,
                hlcTimestamp = row.hlc_timestamp,
                originNodeId = row.origin_node_id,
                baseFhirJson = row.base_fhir_json,
                fhirJson = row.fhir_json,
                syncState = row.sync_state,
                updatedAtMs = row.updated_at_ms
            )
        }
    }

    fun listLocalResourcesForPatient(patientId: String): List<LocalFhirResourceRecord> =
        queries.selectLocalFhirResourcesByPatient(patientId).executeAsList().map { row ->
            LocalFhirResourceRecord(
                resourceKey = row.resource_key,
                facilityId = row.facility_id,
                resourceType = row.resource_type,
                resourceId = row.resource_id,
                patientId = row.patient_id,
                versionId = row.version_id,
                etag = row.etag,
                hlcTimestamp = row.hlc_timestamp,
                originNodeId = row.origin_node_id,
                baseFhirJson = row.base_fhir_json,
                fhirJson = row.fhir_json,
                syncState = row.sync_state,
                updatedAtMs = row.updated_at_ms
            )
        }

    fun enqueueSyncItem(item: QueuedFhirSyncItem) {
        queries.enqueueFhirSyncItem(
            queue_id = item.queueId,
            facility_id = item.facilityId,
            resource_type = item.resourceType,
            resource_id = item.resourceId,
            patient_id = item.patientId,
            operation = item.operation,
            priority = item.priority,
            base_version_id = item.baseVersionId,
            base_etag = item.baseEtag,
            hlc_timestamp = item.hlcTimestamp,
            origin_node_id = item.originNodeId,
            base_payload_json = item.basePayloadJson,
            payload_json = item.payloadJson,
            status = item.status,
            attempt_count = item.attemptCount,
            max_attempts = item.maxAttempts,
            next_attempt_at_ms = item.nextAttemptAtMs,
            last_error = item.lastError,
            created_at_ms = item.createdAtMs
        )
        // Mirror into legacy local_sync_outbox so OfflineVaultSyncRepository remains synchronized
        queries.enqueueOutboxMutation(
            id = item.queueId,
            entity_type = item.resourceType,
            entity_id = item.resourceId,
            operation = item.operation,
            hlc_timestamp = item.hlcTimestamp,
            payload_json = item.payloadJson
        )
    }

    fun findPendingQueueItemsForResource(resourceType: String, resourceId: String): List<QueuedFhirSyncItem> =
        queries.selectPendingFhirSyncQueueByResource(resourceType, resourceId).executeAsList().map(::mapQueueRow)

    fun selectReadyBatch(facilityId: String, nowMs: Long, batchSize: Long): List<QueuedFhirSyncItem> =
        queries.selectReadyFhirSyncQueueBatch(facilityId, nowMs, batchSize).executeAsList().map(::mapQueueRow)

    fun selectAllPending(facilityId: String): List<QueuedFhirSyncItem> =
        queries.selectAllPendingFhirSyncQueue(facilityId).executeAsList().map(::mapQueueRow)

    fun coalesceQueueItem(
        queueId: String,
        newOperation: String,
        newPriority: Long,
        newHlc: String,
        newPayloadJson: String
    ) {
        queries.coalescePendingFhirSyncQueuePayload(
            operation = newOperation,
            priority = newPriority,
            hlc_timestamp = newHlc,
            payload_json = newPayloadJson,
            queue_id = queueId
        )
    }

    fun deleteQueueItem(queueId: String) {
        queries.deleteFhirSyncQueueById(queueId)
        queries.markOutboxSynced(queueId)
    }

    fun updateQueueStatus(queueId: String, status: String, lastError: String? = null) {
        queries.updateFhirSyncQueueStatus(status = status, last_error = lastError, queue_id = queueId)
    }

    fun markQueueItemSynced(queueId: String) {
        queries.markFhirSyncQueueCompleted(queueId)
        queries.markOutboxSynced(queueId)
    }

    fun scheduleRetry(
        queueId: String,
        status: String,
        attemptCount: Long,
        nextAttemptAtMs: Long,
        lastError: String
    ) {
        queries.scheduleFhirSyncRetry(
            status = status,
            attempt_count = attemptCount,
            next_attempt_at_ms = nextAttemptAtMs,
            last_error = lastError,
            queue_id = queueId
        )
    }

    fun insertConflict(conflict: FhirSyncConflictRecord) {
        queries.insertFhirSyncConflict(
            conflict_id = conflict.conflictId,
            queue_id = conflict.queueId,
            facility_id = conflict.facilityId,
            resource_type = conflict.resourceType,
            resource_id = conflict.resourceId,
            patient_id = conflict.patientId,
            conflict_type = conflict.conflictType,
            resolution_strategy = conflict.resolutionStrategy,
            local_hlc = conflict.localHlc,
            remote_hlc = conflict.remoteHlc,
            local_version_id = conflict.localVersionId,
            remote_version_id = conflict.remoteVersionId,
            base_payload_json = conflict.basePayloadJson,
            local_payload_json = conflict.localPayloadJson,
            remote_payload_json = conflict.remotePayloadJson,
            merged_payload_json = conflict.mergedPayloadJson,
            status = conflict.status,
            resolved_by = conflict.resolvedBy,
            resolved_at_ms = conflict.resolvedAtMs,
            detected_at_ms = conflict.detectedAtMs
        )
    }

    fun listOpenConflicts(facilityId: String): List<FhirSyncConflictRecord> =
        queries.selectOpenFhirSyncConflicts(facilityId).executeAsList().map(::mapConflictRow)

    fun getConflictById(conflictId: String): FhirSyncConflictRecord? =
        queries.selectFhirSyncConflictById(conflictId).executeAsOneOrNull()?.let(::mapConflictRow)

    fun markConflictResolved(
        conflictId: String,
        status: String,
        mergedPayloadJson: String?,
        resolvedBy: String,
        resolvedAtMs: Long
    ) {
        queries.resolveFhirSyncConflict(
            status = status,
            merged_payload_json = mergedPayloadJson,
            resolved_by = resolvedBy,
            resolved_at_ms = resolvedAtMs,
            conflict_id = conflictId
        )
    }

    fun currentSyncCursor(facilityId: String): Long =
        queries.selectCheckpoint(facilityId).executeAsOneOrNull()?.last_sync_seq ?: 0L

    fun updateSyncCursor(facilityId: String, nextSeq: Long, serverHlc: String) {
        queries.upsertCheckpoint(facilityId, nextSeq, serverHlc)
    }

    fun enqueueMediaAttachment(record: OfflineMediaAttachmentRecord) {
        queries.enqueueOfflineMediaAttachment(
            media_id = record.mediaId,
            facility_id = record.facilityId,
            patient_id = record.patientId,
            file_name = record.fileName,
            content_type = record.contentType,
            clinical_category = record.clinicalCategory,
            local_encrypted_path = record.localEncryptedPath,
            total_size_bytes = record.totalSizeBytes,
            total_chunks = record.totalChunks,
            uploaded_chunks = record.uploadedChunks,
            remote_upload_id = record.remoteUploadId,
            sha256_hex = record.sha256Hex,
            status = record.status,
            created_at_ms = record.createdAtMs
        )
    }

    fun listPendingMediaAttachments(facilityId: String): List<OfflineMediaAttachmentRecord> =
        queries.selectPendingOfflineMedia(facilityId).executeAsList().map { row ->
            OfflineMediaAttachmentRecord(
                mediaId = row.media_id,
                facilityId = row.facility_id,
                patientId = row.patient_id,
                fileName = row.file_name,
                contentType = row.content_type,
                clinicalCategory = row.clinical_category,
                localEncryptedPath = row.local_encrypted_path,
                totalSizeBytes = row.total_size_bytes,
                totalChunks = row.total_chunks,
                uploadedChunks = row.uploaded_chunks,
                remoteUploadId = row.remote_upload_id,
                sha256Hex = row.sha256_hex,
                status = row.status,
                createdAtMs = row.created_at_ms
            )
        }

    fun updateMediaUploadProgress(
        mediaId: String,
        uploadedChunks: Long,
        remoteUploadId: String?,
        status: String
    ) {
        queries.updateOfflineMediaProgress(
            uploaded_chunks = uploadedChunks,
            remote_upload_id = remoteUploadId,
            status = status,
            media_id = mediaId
        )
    }

    private fun mapQueueRow(row: com.opeshealth.healthcore.mobile.db.Local_fhir_sync_queue): QueuedFhirSyncItem =
        QueuedFhirSyncItem(
            queueId = row.queue_id,
            facilityId = row.facility_id,
            resourceType = row.resource_type,
            resourceId = row.resource_id,
            patientId = row.patient_id,
            operation = row.operation,
            priority = row.priority,
            baseVersionId = row.base_version_id,
            baseEtag = row.base_etag,
            hlcTimestamp = row.hlc_timestamp,
            originNodeId = row.origin_node_id,
            basePayloadJson = row.base_payload_json,
            payloadJson = row.payload_json,
            status = row.status,
            attemptCount = row.attempt_count,
            maxAttempts = row.max_attempts,
            nextAttemptAtMs = row.next_attempt_at_ms,
            lastError = row.last_error,
            createdAtMs = row.created_at_ms
        )

    private fun mapConflictRow(row: com.opeshealth.healthcore.mobile.db.Local_fhir_sync_conflict): FhirSyncConflictRecord =
        FhirSyncConflictRecord(
            conflictId = row.conflict_id,
            queueId = row.queue_id,
            facilityId = row.facility_id,
            resourceType = row.resource_type,
            resourceId = row.resource_id,
            patientId = row.patient_id,
            conflictType = row.conflict_type,
            resolutionStrategy = row.resolution_strategy,
            localHlc = row.local_hlc,
            remoteHlc = row.remote_hlc,
            localVersionId = row.local_version_id,
            remoteVersionId = row.remote_version_id,
            basePayloadJson = row.base_payload_json,
            localPayloadJson = row.local_payload_json,
            remotePayloadJson = row.remote_payload_json,
            mergedPayloadJson = row.merged_payload_json,
            status = row.status,
            resolvedBy = row.resolved_by,
            resolvedAtMs = row.resolved_at_ms,
            detectedAtMs = row.detected_at_ms
        )
}

/**
 * Kotlin Multiplatform Offline FHIR Resource Queue & Conflict Resolution Manager.
 *
 * ### Capabilities
 * 1. **Offline FHIR Resource Persistence & Outbox Queueing (`enqueueOfflineFhirResource`)**:
 *    - Persists offline-created or offline-modified HL7 FHIR R4 resources in SQLDelight (`local_fhir_resource`).
 *    - Assigns offline RFC 9562 UUIDv7 identifiers and monotonic [MobileHybridLogicalClock] timestamps.
 *    - Enqueues prioritized mutations in `local_fhir_sync_queue` with intelligent mutation coalescing
 *      (`CREATE + UPDATE -> CREATE`, `UPDATE + UPDATE -> UPDATE`, `CREATE + DELETE -> NO-OP`).
 * 2. **Single-Flight Background Queue Processing (`processBackgroundSync`)**:
 *    - Dequeues ready batches in priority and causal HLC order, pushing to the HealthCore sync gateway
 *      and pulling incremental server deltas.
 *    - Applies exponential backoff (`baseDelayMs * 2^attempt`) for transient network failures.
 * 3. **Multi-Strategy FHIR Conflict Detection & Resolution**:
 *    - Automatically classifies conflicts by FHIR resource type:
 *      - **High-Risk Clinical Safety Resources** (`AllergyIntolerance`, `MedicationRequest`, `ClinicalNote`,
 *        `Consent`, `Condition`, `MedicationAdministration`) use [FhirConflictResolutionStrategy.CLINICAL_SAFETY_QUARANTINE]
 *        so concurrent edits from another clinician/device are never silently overwritten.
 *      - **Structured Demographic / Encounter Resources** (`Patient`, `Encounter`, `Practitioner`) use
 *        [FhirConflictResolutionStrategy.THREE_WAY_FIELD_MERGE] to merge non-overlapping JSON properties
 *        and additive arrays (`identifier`, `telecom`, `address`) against the common ancestor (`baseFhirJson`).
 *      - **Idempotent Telemetry Resources** (`Observation`) use [FhirConflictResolutionStrategy.HLC_LAST_WRITE_WINS].
 */
class DataSyncManager(
    private val store: SqlDelightFhirSyncStore,
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val nodeId: String = "mobile-kmp-edge-1",
    private val defaultBatchSize: Int = 50,
    private val baseRetryDelayMs: Long = 5_000L,
    private val maxRetryDelayMs: Long = 900_000L
) {
    constructor(
        driverFactory: PlatformSqlDriverFactory,
        baseUrl: String,
        httpClient: HttpClient,
        nodeId: String = "mobile-kmp-edge-1"
    ) : this(
        store = SqlDelightFhirSyncStore(HealthCoreOfflineDatabase(driverFactory.createDriver())),
        baseUrl = baseUrl,
        httpClient = httpClient,
        nodeId = nodeId
    )

    private val syncMutex = Mutex()
    private val hlc = MobileHybridLogicalClock(nodeId)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _telemetry = MutableStateFlow(SyncEngineTelemetry())
    val telemetry: StateFlow<SyncEngineTelemetry> = _telemetry.asStateFlow()

    companion object {
        private val CLINICAL_SAFETY_QUARANTINE_TYPES = setOf(
            "AllergyIntolerance",
            "MedicationRequest",
            "MedicationAdministration",
            "EmarAdministration",
            "ClinicalNote",
            "Consent",
            "Condition",
            "Prescription",
            "ClinicalOrder"
        )

        private val THREE_WAY_MERGEABLE_TYPES = setOf(
            "Patient",
            "Encounter",
            "Practitioner",
            "RelatedPerson",
            "CarePlan",
            "ServiceRequest"
        )

        private val ADDITIVE_UNION_ARRAY_FIELDS = setOf(
            "identifier",
            "telecom",
            "address",
            "category",
            "communication"
        )

        private val CLINICAL_CRITICAL_SCALAR_FIELDS = setOf(
            "status",
            "clinicalStatus",
            "verificationStatus",
            "criticality",
            "dosageInstruction",
            "medicationCodeableConcept"
        )

        /**
         * Compares two Hybrid Logical Clock timestamps formatted as `<physicalMs>:<counter>:<nodeId>`.
         */
        fun compareHlcTimestamps(leftHlc: String, rightHlc: String): Int {
            val lParts = leftHlc.split(":")
            val rParts = rightHlc.split(":")
            val lMs = lParts.getOrNull(0)?.toLongOrNull() ?: 0L
            val rMs = rParts.getOrNull(0)?.toLongOrNull() ?: 0L
            if (lMs != rMs) return lMs.compareTo(rMs)

            val lCnt = lParts.getOrNull(1)?.toIntOrNull() ?: 0
            val rCnt = rParts.getOrNull(1)?.toIntOrNull() ?: 0
            if (lCnt != rCnt) return lCnt.compareTo(rCnt)

            val lNode = lParts.getOrNull(2) ?: ""
            val rNode = rParts.getOrNull(2) ?: ""
            return lNode.compareTo(rNode)
        }
    }

    /**
     * Resolves the default [FhirConflictResolutionStrategy] for a given HL7 FHIR R4 resource type.
     */
    fun defaultStrategyForResource(resourceType: String): FhirConflictResolutionStrategy = when {
        CLINICAL_SAFETY_QUARANTINE_TYPES.contains(resourceType) ->
            FhirConflictResolutionStrategy.CLINICAL_SAFETY_QUARANTINE
        THREE_WAY_MERGEABLE_TYPES.contains(resourceType) ->
            FhirConflictResolutionStrategy.THREE_WAY_FIELD_MERGE
        else ->
            FhirConflictResolutionStrategy.HLC_LAST_WRITE_WINS
    }

    /**
     * Persists an offline-created or offline-updated HL7 FHIR R4 resource into SQLDelight
     * (`local_fhir_resource`) and enqueues or coalesces its mutation in `local_fhir_sync_queue`.
     */
    fun enqueueOfflineFhirResource(
        facilityId: String,
        resourceType: String,
        fhirResourceJson: String,
        operation: String = "UPSERT",
        explicitResourceId: String? = null,
        explicitPatientId: String? = null,
        priorityOverride: FhirSyncPriority? = null,
        wallClockMs: Long
    ): QueuedFhirSyncItem? {
        val parsedObj = runCatching { json.parseToJsonElement(fhirResourceJson).jsonObject }
            .getOrElse { buildJsonObject { put("resourceType", resourceType) } }

        val resolvedId = explicitResourceId
            ?: parsedObj["id"]?.jsonPrimitive?.contentOrNull
            ?: hlc.nextUuidV7(wallClockMs)
        val resolvedPatientId = explicitPatientId ?: extractPatientIdFromFhirObject(resourceType, resolvedId, parsedObj)
        val hlcStamp = hlc.nextStamp(wallClockMs)

        val existingLocal = store.getLocalResource(resourceType, resolvedId)
        val baseVersion = existingLocal?.versionId ?: 1L
        val nextLocalVersion = if (existingLocal == null) 1L else existingLocal.versionId + 1L
        val baseEtag = existingLocal?.etag ?: "W/\"1\""
        val nextEtag = "W/\"$nextLocalVersion\""

        val enrichedFhirJson = enrichFhirJsonWithIdAndMeta(
            original = parsedObj,
            resourceType = resourceType,
            resourceId = resolvedId,
            versionId = nextLocalVersion,
            hlcStamp = hlcStamp
        )

        val normalizedOp = operation.trim().uppercase()
        val priority = (priorityOverride ?: FhirSyncPriority.forResourceType(resourceType)).level
        val resourceKey = "$resourceType/$resolvedId"

        // Update local SQLDelight FHIR resource cache
        store.upsertLocalResource(
            LocalFhirResourceRecord(
                resourceKey = resourceKey,
                facilityId = facilityId,
                resourceType = resourceType,
                resourceId = resolvedId,
                patientId = resolvedPatientId,
                versionId = nextLocalVersion,
                etag = nextEtag,
                hlcTimestamp = hlcStamp,
                originNodeId = nodeId,
                baseFhirJson = existingLocal?.baseFhirJson ?: existingLocal?.fhirJson,
                fhirJson = enrichedFhirJson,
                syncState = if (normalizedOp == "DELETE") "DELETED_TOMBSTONE" else "PENDING_SYNC",
                updatedAtMs = wallClockMs
            )
        )

        // Check for an existing unsynced queue item for the same resource to coalesce offline edits
        val pendingForResource = store.findPendingQueueItemsForResource(resourceType, resolvedId)
        if (pendingForResource.isNotEmpty()) {
            val existingQueueItem = pendingForResource.first()
            if (existingQueueItem.operation == "CREATE" && normalizedOp == "DELETE") {
                // Created and deleted while still offline -> cancel out queue item completely
                store.deleteQueueItem(existingQueueItem.queueId)
                refreshTelemetry(facilityId, wallClockMs)
                return null
            }
            val coalescedOp = when {
                existingQueueItem.operation == "CREATE" && normalizedOp != "DELETE" -> "CREATE"
                normalizedOp == "DELETE" -> "DELETE"
                else -> normalizedOp
            }
            val mergedPayload = if (coalescedOp == "DELETE") {
                enrichedFhirJson
            } else {
                mergeJsonObjectsShallow(existingQueueItem.payloadJson, enrichedFhirJson)
            }
            val effectivePriority = min(existingQueueItem.priority, priority)
            store.coalesceQueueItem(
                queueId = existingQueueItem.queueId,
                newOperation = coalescedOp,
                newPriority = effectivePriority,
                newHlc = hlcStamp,
                newPayloadJson = mergedPayload
            )
            refreshTelemetry(facilityId, wallClockMs)
            return existingQueueItem.copy(
                operation = coalescedOp,
                priority = effectivePriority,
                hlcTimestamp = hlcStamp,
                payloadJson = mergedPayload
            )
        }

        val queueItem = QueuedFhirSyncItem(
            queueId = hlc.nextUuidV7(wallClockMs),
            facilityId = facilityId,
            resourceType = resourceType,
            resourceId = resolvedId,
            patientId = resolvedPatientId,
            operation = normalizedOp,
            priority = priority,
            baseVersionId = baseVersion,
            baseEtag = baseEtag,
            hlcTimestamp = hlcStamp,
            originNodeId = nodeId,
            basePayloadJson = existingLocal?.baseFhirJson ?: existingLocal?.fhirJson,
            payloadJson = enrichedFhirJson,
            status = "QUEUED",
            attemptCount = 0L,
            maxAttempts = 5L,
            nextAttemptAtMs = 0L,
            lastError = null,
            createdAtMs = wallClockMs
        )
        store.enqueueSyncItem(queueItem)
        refreshTelemetry(facilityId, wallClockMs)
        return queueItem
    }

    /**
     * Drains the SQLDelight background FHIR sync queue and pulls remote deltas with automatic
     * conflict detection and resolution. Guarded by a coroutine [Mutex] so concurrent triggers
     * (WorkManager, BGTaskScheduler, connectivity callbacks) never duplicate network pushes.
     */
    suspend fun processBackgroundSync(
        facilityId: String,
        bearerToken: String,
        wallClockMs: Long,
        strategyOverride: FhirConflictResolutionStrategy? = null
    ): Result<SyncBatchExecutionSummary> = syncMutex.withLock {
        runCatching {
            _telemetry.value = _telemetry.value.copy(
                state = SyncManagerLifecycleState.DRAINING_QUEUE,
                lastErrorMessage = null
            )

            val readyBatch = store.selectReadyBatch(
                facilityId = facilityId,
                nowMs = wallClockMs,
                batchSize = defaultBatchSize.toLong()
            )

            var pushedCount = 0
            var autoMergedCount = 0
            var quarantinedCount = 0
            var retryScheduledCount = 0

            if (readyBatch.isNotEmpty()) {
                readyBatch.forEach { store.updateQueueStatus(it.queueId, "IN_FLIGHT") }

                val outgoingPayloads = readyBatch.map { item ->
                    OfflineMutationPayload(
                        originNodeId = item.originNodeId,
                        entityType = item.resourceType,
                        entityId = item.resourceId,
                        operation = if (item.operation == "DELETE") "DELETE" else "UPSERT",
                        hlcTimestamp = item.hlcTimestamp,
                        payloadJson = item.payloadJson
                    )
                }

                val pushResponse = runCatching {
                    httpClient.post("$baseUrl/api/v1/sync/push?facilityId=$facilityId") {
                        header(HttpHeaders.Authorization, "Bearer $bearerToken")
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(outgoingPayloads))
                    }
                }.getOrNull()

                if (pushResponse != null && pushResponse.status.isSuccess()) {
                    val bodyObj = runCatching {
                        json.parseToJsonElement(pushResponse.bodyAsText()).jsonObject
                    }.getOrNull()
                    val serverConflicts = bodyObj?.get("conflictCount")?.jsonPrimitive?.intOrNull ?: 0

                    for (item in readyBatch) {
                        val strategy = strategyOverride ?: defaultStrategyForResource(item.resourceType)
                        if (serverConflicts > 0 && CLINICAL_SAFETY_QUARANTINE_TYPES.contains(item.resourceType)) {
                            val serverHlc = bodyObj?.get("serverHlc")?.jsonPrimitive?.contentOrNull
                                ?: hlc.nextStamp(wallClockMs)
                            handleDetectedConflict(
                                queueItem = item,
                                remotePayloadJson = item.basePayloadJson ?: item.payloadJson,
                                remoteHlc = serverHlc,
                                remoteVersionId = item.baseVersionId + 1L,
                                conflictType = "CONCURRENT_CLINICAL_SAFETY_EDIT",
                                strategy = strategy,
                                wallClockMs = wallClockMs
                            ).also { outcome ->
                                if (outcome == ConflictHandlingOutcome.QUARANTINED) quarantinedCount++
                                else if (outcome == ConflictHandlingOutcome.AUTO_MERGED) autoMergedCount++
                            }
                        } else {
                            store.markQueueItemSynced(item.queueId)
                            markLocalResourceSynced(item, wallClockMs)
                            pushedCount++
                        }
                    }
                } else if (pushResponse != null &&
                    (pushResponse.status == HttpStatusCode.Conflict ||
                        pushResponse.status == HttpStatusCode.PreconditionFailed)
                ) {
                    val serverBody = runCatching { pushResponse.bodyAsText() }.getOrDefault("{}")
                    for (item in readyBatch) {
                        val strategy = strategyOverride ?: defaultStrategyForResource(item.resourceType)
                        val outcome = handleDetectedConflict(
                            queueItem = item,
                            remotePayloadJson = serverBody.ifBlank { item.basePayloadJson ?: "{}" },
                            remoteHlc = hlc.nextStamp(wallClockMs),
                            remoteVersionId = item.baseVersionId + 1L,
                            conflictType = "ETAG_VERSION_MISMATCH",
                            strategy = strategy,
                            wallClockMs = wallClockMs
                        )
                        if (outcome == ConflictHandlingOutcome.QUARANTINED) quarantinedCount++
                        else if (outcome == ConflictHandlingOutcome.AUTO_MERGED) autoMergedCount++
                    }
                } else {
                    val errText = pushResponse?.let { "HTTP ${it.status.value}" } ?: "Network unreachable"
                    for (item in readyBatch) {
                        scheduleExponentialBackoffRetry(item, errText, wallClockMs)
                        retryScheduledCount++
                    }
                }
            }

            // Pull incremental deltas from server and reconcile with local SQLDelight FHIR cache
            _telemetry.value = _telemetry.value.copy(state = SyncManagerLifecycleState.PULLING_DELTAS)
            val currentSeq = store.currentSyncCursor(facilityId)
            var latestSeq = currentSeq
            var pulledCount = 0

            val pullResponse = runCatching {
                httpClient.get("$baseUrl/api/v1/sync/pull?facilityId=$facilityId&sinceSeq=$currentSeq") {
                    header(HttpHeaders.Authorization, "Bearer $bearerToken")
                }
            }.getOrNull()

            if (pullResponse != null && pullResponse.status.isSuccess()) {
                val root = json.parseToJsonElement(pullResponse.bodyAsText()).jsonObject
                latestSeq = root["nextSyncSeq"]?.jsonPrimitive?.longOrNull ?: currentSeq
                val serverHlc = root["serverHlc"]?.jsonPrimitive?.contentOrNull ?: hlc.nextStamp(wallClockMs)

                root["deltas"]?.jsonArray?.forEach { elem ->
                    val delta = elem.jsonObject
                    val resourceType = delta["entityType"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val resourceId = delta["entityId"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val operation = delta["operation"]?.jsonPrimitive?.contentOrNull ?: "UPSERT"
                    val remotePayload = delta["payloadJson"]?.jsonPrimitive?.contentOrNull ?: "{}"
                    val remoteHlc = delta["hlcTimestamp"]?.jsonPrimitive?.contentOrNull ?: serverHlc
                    val originNode = delta["originNodeId"]?.jsonPrimitive?.contentOrNull ?: "server"

                    if (originNode == nodeId) {
                        return@forEach
                    }

                    pulledCount++
                    val reconciliation = reconcileIncomingRemoteDelta(
                        facilityId = facilityId,
                        resourceType = resourceType,
                        resourceId = resourceId,
                        operation = operation,
                        remotePayloadJson = remotePayload,
                        remoteHlc = remoteHlc,
                        strategyOverride = strategyOverride,
                        wallClockMs = wallClockMs
                    )
                    if (reconciliation == ConflictHandlingOutcome.QUARANTINED) quarantinedCount++
                    else if (reconciliation == ConflictHandlingOutcome.AUTO_MERGED) autoMergedCount++
                }

                store.updateSyncCursor(facilityId, latestSeq, serverHlc)
            }

            val remainingQueue = store.selectAllPending(facilityId).size
            val openConflicts = store.listOpenConflicts(facilityId).size
            val finishHlc = hlc.nextStamp(wallClockMs)

            _telemetry.value = SyncEngineTelemetry(
                state = SyncManagerLifecycleState.IDLE,
                queuedCount = remainingQueue,
                openConflictCount = openConflicts,
                lastSyncedSeq = latestSeq,
                lastSyncWallClockMs = wallClockMs,
                lastErrorMessage = null
            )

            SyncBatchExecutionSummary(
                facilityId = facilityId,
                pushedCount = pushedCount,
                pulledCount = pulledCount,
                autoMergedCount = autoMergedCount,
                quarantinedConflictCount = quarantinedCount,
                retryScheduledCount = retryScheduledCount,
                remainingQueueDepth = remainingQueue,
                latestSyncSeq = latestSeq,
                completedAtHlc = finishHlc
            )
        }.onFailure { err ->
            _telemetry.value = _telemetry.value.copy(
                state = SyncManagerLifecycleState.ERROR,
                lastErrorMessage = err.message ?: "Background sync error"
            )
        }
    }

    /**
     * Reconciles an incoming remote FHIR resource delta against the local SQLDelight store.
     * If the local resource has unsynced offline edits (`PENDING_SYNC`), invokes the configured
     * conflict resolution strategy (`CLINICAL_SAFETY_QUARANTINE`, `THREE_WAY_FIELD_MERGE`,
     * `HLC_LAST_WRITE_WINS`, `CLIENT_WINS`, or `SERVER_WINS`).
     */
    fun reconcileIncomingRemoteDelta(
        facilityId: String,
        resourceType: String,
        resourceId: String,
        operation: String,
        remotePayloadJson: String,
        remoteHlc: String,
        strategyOverride: FhirConflictResolutionStrategy? = null,
        wallClockMs: Long
    ): ConflictHandlingOutcome {
        val localRecord = store.getLocalResource(resourceType, resourceId)
        val pendingQueueItems = store.findPendingQueueItemsForResource(resourceType, resourceId)
        val hasUnsyncedLocalChanges = localRecord?.syncState == "PENDING_SYNC" || pendingQueueItems.isNotEmpty()

        if (!hasUnsyncedLocalChanges) {
            applyRemoteResourceLocally(
                facilityId = facilityId,
                resourceType = resourceType,
                resourceId = resourceId,
                operation = operation,
                remotePayloadJson = remotePayloadJson,
                remoteHlc = remoteHlc,
                nextVersionId = (localRecord?.versionId ?: 0L) + 1L,
                wallClockMs = wallClockMs
            )
            refreshTelemetry(facilityId, wallClockMs)
            return ConflictHandlingOutcome.APPLIED_CLEANLY
        }

        val activeQueueItem = pendingQueueItems.firstOrNull() ?: QueuedFhirSyncItem(
            queueId = hlc.nextUuidV7(wallClockMs),
            facilityId = facilityId,
            resourceType = resourceType,
            resourceId = resourceId,
            patientId = localRecord?.patientId,
            operation = "UPDATE",
            priority = FhirSyncPriority.forResourceType(resourceType).level,
            baseVersionId = localRecord?.versionId ?: 1L,
            baseEtag = localRecord?.etag,
            hlcTimestamp = localRecord?.hlcTimestamp ?: hlc.nextStamp(wallClockMs),
            originNodeId = nodeId,
            basePayloadJson = localRecord?.baseFhirJson,
            payloadJson = localRecord?.fhirJson ?: "{}",
            status = "QUEUED",
            attemptCount = 0L,
            maxAttempts = 5L,
            nextAttemptAtMs = 0L,
            lastError = null,
            createdAtMs = wallClockMs
        )

        val conflictType = if (operation.equals("DELETE", ignoreCase = true) || activeQueueItem.operation == "DELETE") {
            "DELETE_MODIFIED_CONFLICT"
        } else {
            "HLC_CAUSAL_DIVERGENCE"
        }
        val strategy = strategyOverride ?: defaultStrategyForResource(resourceType)

        return handleDetectedConflict(
            queueItem = activeQueueItem,
            remotePayloadJson = remotePayloadJson,
            remoteHlc = remoteHlc,
            remoteVersionId = (localRecord?.versionId ?: 1L) + 1L,
            conflictType = conflictType,
            strategy = strategy,
            wallClockMs = wallClockMs
        )
    }

    enum class ConflictHandlingOutcome {
        APPLIED_CLEANLY,
        AUTO_MERGED,
        QUARANTINED,
        CLIENT_WON,
        SERVER_WON
    }

    private fun handleDetectedConflict(
        queueItem: QueuedFhirSyncItem,
        remotePayloadJson: String,
        remoteHlc: String,
        remoteVersionId: Long,
        conflictType: String,
        strategy: FhirConflictResolutionStrategy,
        wallClockMs: Long
    ): ConflictHandlingOutcome {
        _telemetry.value = _telemetry.value.copy(state = SyncManagerLifecycleState.RESOLVING_CONFLICTS)

        return when (strategy) {
            FhirConflictResolutionStrategy.CLINICAL_SAFETY_QUARANTINE -> {
                quarantineConflictInSqlDelight(
                    queueItem = queueItem,
                    remotePayloadJson = remotePayloadJson,
                    remoteHlc = remoteHlc,
                    remoteVersionId = remoteVersionId,
                    conflictType = conflictType,
                    strategy = strategy,
                    wallClockMs = wallClockMs
                )
                ConflictHandlingOutcome.QUARANTINED
            }

            FhirConflictResolutionStrategy.THREE_WAY_FIELD_MERGE -> {
                val mergeAttempt = performThreeWayFhirMerge(
                    baseJson = queueItem.basePayloadJson,
                    localJson = queueItem.payloadJson,
                    remoteJson = remotePayloadJson,
                    localHlc = queueItem.hlcTimestamp,
                    remoteHlc = remoteHlc
                )
                if (mergeAttempt.requiresManualClinicalQuarantine) {
                    quarantineConflictInSqlDelight(
                        queueItem = queueItem,
                        remotePayloadJson = remotePayloadJson,
                        remoteHlc = remoteHlc,
                        remoteVersionId = remoteVersionId,
                        conflictType = "THREE_WAY_CLINICAL_FIELD_COLLISION",
                        strategy = FhirConflictResolutionStrategy.CLINICAL_SAFETY_QUARANTINE,
                        wallClockMs = wallClockMs
                    )
                    ConflictHandlingOutcome.QUARANTINED
                } else {
                    val mergedHlc = hlc.nextStamp(wallClockMs)
                    val mergedVersion = maxOf(queueItem.baseVersionId, remoteVersionId) + 1L
                    store.coalesceQueueItem(
                        queueId = queueItem.queueId,
                        newOperation = "UPSERT",
                        newPriority = queueItem.priority,
                        newHlc = mergedHlc,
                        newPayloadJson = mergeAttempt.mergedJson
                    )
                    store.upsertLocalResource(
                        LocalFhirResourceRecord(
                            resourceKey = "${queueItem.resourceType}/${queueItem.resourceId}",
                            facilityId = queueItem.facilityId,
                            resourceType = queueItem.resourceType,
                            resourceId = queueItem.resourceId,
                            patientId = queueItem.patientId,
                            versionId = mergedVersion,
                            etag = "W/\"$mergedVersion\"",
                            hlcTimestamp = mergedHlc,
                            originNodeId = nodeId,
                            baseFhirJson = remotePayloadJson,
                            fhirJson = mergeAttempt.mergedJson,
                            syncState = "PENDING_SYNC",
                            updatedAtMs = wallClockMs
                        )
                    )
                    // Record auto-resolved audit row in local_fhir_sync_conflict
                    store.insertConflict(
                        FhirSyncConflictRecord(
                            conflictId = hlc.nextUuidV7(wallClockMs),
                            queueId = queueItem.queueId,
                            facilityId = queueItem.facilityId,
                            resourceType = queueItem.resourceType,
                            resourceId = queueItem.resourceId,
                            patientId = queueItem.patientId,
                            conflictType = conflictType,
                            resolutionStrategy = strategy.name,
                            localHlc = queueItem.hlcTimestamp,
                            remoteHlc = remoteHlc,
                            localVersionId = queueItem.baseVersionId,
                            remoteVersionId = remoteVersionId,
                            basePayloadJson = queueItem.basePayloadJson,
                            localPayloadJson = queueItem.payloadJson,
                            remotePayloadJson = remotePayloadJson,
                            mergedPayloadJson = mergeAttempt.mergedJson,
                            status = "AUTO_RESOLVED",
                            resolvedBy = "DataSyncManager-3WayMerge",
                            resolvedAtMs = wallClockMs,
                            detectedAtMs = wallClockMs
                        )
                    )
                    refreshTelemetry(queueItem.facilityId, wallClockMs)
                    ConflictHandlingOutcome.AUTO_MERGED
                }
            }

            FhirConflictResolutionStrategy.HLC_LAST_WRITE_WINS -> {
                val localWins = compareHlcTimestamps(queueItem.hlcTimestamp, remoteHlc) >= 0
                if (localWins) {
                    promoteLocalClientWinner(queueItem, remotePayloadJson, remoteVersionId, wallClockMs)
                    ConflictHandlingOutcome.CLIENT_WON
                } else {
                    acceptRemoteServerWinner(queueItem, remotePayloadJson, remoteHlc, remoteVersionId, wallClockMs)
                    ConflictHandlingOutcome.SERVER_WON
                }
            }

            FhirConflictResolutionStrategy.CLIENT_WINS -> {
                promoteLocalClientWinner(queueItem, remotePayloadJson, remoteVersionId, wallClockMs)
                ConflictHandlingOutcome.CLIENT_WON
            }

            FhirConflictResolutionStrategy.SERVER_WINS -> {
                acceptRemoteServerWinner(queueItem, remotePayloadJson, remoteHlc, remoteVersionId, wallClockMs)
                ConflictHandlingOutcome.SERVER_WON
            }
        }
    }

    /**
     * Resolves an `OPEN` quarantined conflict in SQLDelight using an explicit clinician or
     * privacy officer decision ([FhirConflictResolutionDecision]).
     */
    fun resolveQuarantinedConflict(
        conflictId: String,
        decision: FhirConflictResolutionDecision,
        resolvedBy: String,
        wallClockMs: Long
    ): FhirSyncConflictRecord {
        val conflict = store.getConflictById(conflictId)
            ?: throw IllegalArgumentException("FHIR sync conflict not found in SQLDelight: $conflictId")

        val nextVersion = maxOf(conflict.localVersionId, conflict.remoteVersionId) + 1L
        val nextHlc = hlc.nextStamp(wallClockMs)
        val resourceKey = "${conflict.resourceType}/${conflict.resourceId}"

        val (newStatus, winningJson, reEnqueueForPush) = when (decision) {
            is FhirConflictResolutionDecision.AcceptLocalClient -> Triple(
                "RESOLVED_CLIENT_WINS",
                conflict.localPayloadJson,
                true
            )
            is FhirConflictResolutionDecision.KeepRemoteServer -> Triple(
                "RESOLVED_SERVER_WINS",
                conflict.remotePayloadJson,
                false
            )
            is FhirConflictResolutionDecision.ApplyMergedFhirJson -> Triple(
                "RESOLVED_MERGED",
                decision.mergedFhirJson,
                true
            )
        }

        store.markConflictResolved(
            conflictId = conflictId,
            status = newStatus,
            mergedPayloadJson = winningJson,
            resolvedBy = resolvedBy,
            resolvedAtMs = wallClockMs
        )

        store.upsertLocalResource(
            LocalFhirResourceRecord(
                resourceKey = resourceKey,
                facilityId = conflict.facilityId,
                resourceType = conflict.resourceType,
                resourceId = conflict.resourceId,
                patientId = conflict.patientId,
                versionId = nextVersion,
                etag = "W/\"$nextVersion\"",
                hlcTimestamp = nextHlc,
                originNodeId = nodeId,
                baseFhirJson = conflict.remotePayloadJson,
                fhirJson = winningJson,
                syncState = if (reEnqueueForPush) "PENDING_SYNC" else "SYNCED",
                updatedAtMs = wallClockMs
            )
        )

        if (reEnqueueForPush) {
            store.coalesceQueueItem(
                queueId = conflict.queueId,
                newOperation = "UPSERT",
                newPriority = FhirSyncPriority.forResourceType(conflict.resourceType).level,
                newHlc = nextHlc,
                newPayloadJson = winningJson
            )
        } else {
            store.markQueueItemSynced(conflict.queueId)
        }

        refreshTelemetry(conflict.facilityId, wallClockMs)
        return store.getConflictById(conflictId) ?: conflict.copy(
            status = newStatus,
            mergedPayloadJson = winningJson,
            resolvedBy = resolvedBy,
            resolvedAtMs = wallClockMs
        )
    }

    /**
     * Performs a 3-way structural FHIR JSON merge across `baseJson`, `localJson`, and `remoteJson`.
     */
    data class ThreeWayMergeOutcome(
        val mergedJson: String,
        val requiresManualClinicalQuarantine: Boolean
    )

    fun performThreeWayFhirMerge(
        baseJson: String?,
        localJson: String,
        remoteJson: String,
        localHlc: String,
        remoteHlc: String
    ): ThreeWayMergeOutcome {
        val baseObj = runCatching {
            if (baseJson.isNullOrBlank()) JsonObject(emptyMap())
            else json.parseToJsonElement(baseJson).jsonObject
        }.getOrElse { JsonObject(emptyMap()) }

        val localObj = runCatching { json.parseToJsonElement(localJson).jsonObject }
            .getOrElse { return ThreeWayMergeOutcome(localJson, false) }
        val remoteObj = runCatching { json.parseToJsonElement(remoteJson).jsonObject }
            .getOrElse { return ThreeWayMergeOutcome(localJson, false) }

        val allKeys = (baseObj.keys + localObj.keys + remoteObj.keys).toSet()
        val mergedMap = LinkedHashMap<String, JsonElement>()
        var clinicalFieldCollision = false
        val localIsNewer = compareHlcTimestamps(localHlc, remoteHlc) >= 0

        for (key in allKeys) {
            if (key == "meta") {
                continue
            }
            val baseVal = baseObj[key]
            val localVal = localObj[key]
            val remoteVal = remoteObj[key]

            when {
                localVal == remoteVal -> {
                    if (localVal != null) mergedMap[key] = localVal
                }
                localVal == baseVal -> {
                    // Only remote changed this field
                    if (remoteVal != null) mergedMap[key] = remoteVal
                }
                remoteVal == baseVal -> {
                    // Only local changed this field
                    if (localVal != null) mergedMap[key] = localVal
                }
                else -> {
                    // Both local and remote changed this field concurrently
                    if (ADDITIVE_UNION_ARRAY_FIELDS.contains(key) &&
                        localVal is JsonArray && remoteVal is JsonArray
                    ) {
                        val unionSet = LinkedHashSet<JsonElement>()
                        remoteVal.forEach { unionSet.add(it) }
                        localVal.forEach { unionSet.add(it) }
                        mergedMap[key] = buildJsonArray { unionSet.forEach { add(it) } }
                    } else if (CLINICAL_CRITICAL_SCALAR_FIELDS.contains(key)) {
                        clinicalFieldCollision = true
                        mergedMap[key] = if (localIsNewer) (localVal ?: remoteVal!!) else (remoteVal ?: localVal!!)
                    } else {
                        val chosen = if (localIsNewer) (localVal ?: remoteVal) else (remoteVal ?: localVal)
                        if (chosen != null) mergedMap[key] = chosen
                    }
                }
            }
        }

        val mergedObj = JsonObject(mergedMap)
        return ThreeWayMergeOutcome(
            mergedJson = json.encodeToString(JsonObject.serializer(), mergedObj),
            requiresManualClinicalQuarantine = clinicalFieldCollision
        )
    }

    fun listPendingSyncQueue(facilityId: String): List<QueuedFhirSyncItem> =
        store.selectAllPending(facilityId)

    fun listOpenConflicts(facilityId: String): List<FhirSyncConflictRecord> =
        store.listOpenConflicts(facilityId)

    fun getCachedFhirResource(resourceType: String, resourceId: String): LocalFhirResourceRecord? =
        store.getLocalResource(resourceType, resourceId)

    fun listCachedPatientResources(patientId: String): List<LocalFhirResourceRecord> =
        store.listLocalResourcesForPatient(patientId)

    /**
     * Enqueues a locally encrypted clinical attachment (wound photo, referral PDF, ultrasound clip)
     * in SQLDelight (`local_fhir_media_queue`) for resumable chunked upload to `/api/v1/sync/media/**`.
     */
    fun enqueueOfflineEncryptedAttachment(
        facilityId: String,
        patientId: String,
        fileName: String,
        contentType: String,
        clinicalCategory: String,
        localEncryptedPath: String,
        totalSizeBytes: Long,
        totalChunks: Long,
        sha256Hex: String,
        wallClockMs: Long
    ): OfflineMediaAttachmentRecord {
        val record = OfflineMediaAttachmentRecord(
            mediaId = hlc.nextUuidV7(wallClockMs),
            facilityId = facilityId,
            patientId = patientId,
            fileName = fileName,
            contentType = contentType,
            clinicalCategory = clinicalCategory,
            localEncryptedPath = localEncryptedPath,
            totalSizeBytes = totalSizeBytes,
            totalChunks = totalChunks.coerceAtLeast(1L),
            uploadedChunks = 0L,
            remoteUploadId = null,
            sha256Hex = sha256Hex,
            status = "QUEUED",
            createdAtMs = wallClockMs
        )
        store.enqueueMediaAttachment(record)
        return record
    }

    fun listPendingOfflineMedia(facilityId: String): List<OfflineMediaAttachmentRecord> =
        store.listPendingMediaAttachments(facilityId)

    /**
     * Resumes uploading pending encrypted offline clinical media attachments in `local_fhir_media_queue`
     * against the Spring Boot `/api/v1/sync/media/init`, `/chunks`, and `/complete` endpoints.
     *
     * @param chunkBase64Provider supplies the Base64-encoded encrypted chunk bytes for `(localEncryptedPath, chunkIndex)`.
     */
    suspend fun syncPendingEncryptedMedia(
        facilityId: String,
        bearerToken: String,
        chunkBase64Provider: suspend (localEncryptedPath: String, chunkIndex: Int) -> String?
    ): Result<OfflineMediaSyncSummary> = syncMutex.withLock {
        runCatching {
            val pendingMedia = store.listPendingMediaAttachments(facilityId)
            var completedAttachments = 0
            var uploadedChunksCount = 0
            var failedAttachments = 0

            for (item in pendingMedia) {
                var uploadId = item.remoteUploadId
                if (uploadId.isNullOrBlank()) {
                    val initPayload = buildJsonObject {
                        put("facilityId", item.facilityId)
                        put("patientId", item.patientId)
                        put("originNodeId", nodeId)
                        put("fileName", item.fileName)
                        put("contentType", item.contentType)
                        put("clinicalCategory", item.clinicalCategory)
                        put("totalChunks", item.totalChunks.toInt())
                        put("totalSizeBytes", item.totalSizeBytes)
                        put("expectedSha256Hex", item.sha256Hex)
                        put("clientEncryptedAesGcm", true)
                    }
                    val initResp = runCatching {
                        httpClient.post("$baseUrl/api/v1/sync/media/init") {
                            header(HttpHeaders.Authorization, "Bearer $bearerToken")
                            contentType(ContentType.Application.Json)
                            setBody(json.encodeToString(JsonObject.serializer(), initPayload))
                        }
                    }.getOrNull()

                    if (initResp == null || !initResp.status.isSuccess()) {
                        failedAttachments++
                        continue
                    }
                    val initObj = runCatching { json.parseToJsonElement(initResp.bodyAsText()).jsonObject }.getOrNull()
                    uploadId = initObj?.get("uploadId")?.jsonPrimitive?.contentOrNull
                    if (uploadId.isNullOrBlank()) {
                        failedAttachments++
                        continue
                    }
                    store.updateMediaUploadProgress(
                        mediaId = item.mediaId,
                        uploadedChunks = item.uploadedChunks,
                        remoteUploadId = uploadId,
                        status = "UPLOADING"
                    )
                }

                var currentUploaded = item.uploadedChunks.toInt()
                var chunkFailure = false
                for (chunkIdx in currentUploaded until item.totalChunks.toInt()) {
                    val chunkBase64 = chunkBase64Provider(item.localEncryptedPath, chunkIdx)
                    if (chunkBase64.isNullOrBlank()) {
                        chunkFailure = true
                        break
                    }
                    val chunkPayload = buildJsonObject {
                        put("chunkIndex", chunkIdx)
                        put("chunkBase64Data", chunkBase64)
                    }
                    val chunkResp = runCatching {
                        httpClient.post("$baseUrl/api/v1/sync/media/$uploadId/chunks") {
                            header(HttpHeaders.Authorization, "Bearer $bearerToken")
                            contentType(ContentType.Application.Json)
                            setBody(json.encodeToString(JsonObject.serializer(), chunkPayload))
                        }
                    }.getOrNull()

                    if (chunkResp == null || !chunkResp.status.isSuccess()) {
                        chunkFailure = true
                        break
                    }
                    currentUploaded = chunkIdx + 1
                    uploadedChunksCount++
                    store.updateMediaUploadProgress(
                        mediaId = item.mediaId,
                        uploadedChunks = currentUploaded.toLong(),
                        remoteUploadId = uploadId,
                        status = "UPLOADING"
                    )
                }

                if (chunkFailure) {
                    failedAttachments++
                    continue
                }

                val completeResp = runCatching {
                    httpClient.post("$baseUrl/api/v1/sync/media/$uploadId/complete") {
                        header(HttpHeaders.Authorization, "Bearer $bearerToken")
                    }
                }.getOrNull()

                if (completeResp != null && completeResp.status.isSuccess()) {
                    store.updateMediaUploadProgress(
                        mediaId = item.mediaId,
                        uploadedChunks = item.totalChunks,
                        remoteUploadId = uploadId,
                        status = "COMPLETED"
                    )
                    completedAttachments++
                } else {
                    failedAttachments++
                }
            }

            OfflineMediaSyncSummary(
                facilityId = facilityId,
                completedAttachments = completedAttachments,
                uploadedChunksCount = uploadedChunksCount,
                failedAttachments = failedAttachments
            )
        }
    }

    private fun quarantineConflictInSqlDelight(
        queueItem: QueuedFhirSyncItem,
        remotePayloadJson: String,
        remoteHlc: String,
        remoteVersionId: Long,
        conflictType: String,
        strategy: FhirConflictResolutionStrategy,
        wallClockMs: Long
    ) {
        val conflictId = hlc.nextUuidV7(wallClockMs)
        store.updateQueueStatus(
            queueId = queueItem.queueId,
            status = "CONFLICT_DETECTED",
            lastError = "Quarantined for clinician review ($conflictType)"
        )
        store.insertConflict(
            FhirSyncConflictRecord(
                conflictId = conflictId,
                queueId = queueItem.queueId,
                facilityId = queueItem.facilityId,
                resourceType = queueItem.resourceType,
                resourceId = queueItem.resourceId,
                patientId = queueItem.patientId,
                conflictType = conflictType,
                resolutionStrategy = strategy.name,
                localHlc = queueItem.hlcTimestamp,
                remoteHlc = remoteHlc,
                localVersionId = queueItem.baseVersionId,
                remoteVersionId = remoteVersionId,
                basePayloadJson = queueItem.basePayloadJson,
                localPayloadJson = queueItem.payloadJson,
                remotePayloadJson = remotePayloadJson,
                mergedPayloadJson = null,
                status = "OPEN",
                resolvedBy = null,
                resolvedAtMs = null,
                detectedAtMs = wallClockMs
            )
        )
        val existingLocal = store.getLocalResource(queueItem.resourceType, queueItem.resourceId)
        if (existingLocal != null) {
            store.upsertLocalResource(existingLocal.copy(syncState = "CONFLICT_QUARANTINED", updatedAtMs = wallClockMs))
        }
        refreshTelemetry(queueItem.facilityId, wallClockMs)
    }

    private fun promoteLocalClientWinner(
        queueItem: QueuedFhirSyncItem,
        remotePayloadJson: String,
        remoteVersionId: Long,
        wallClockMs: Long
    ) {
        val bumpedVersion = maxOf(queueItem.baseVersionId, remoteVersionId) + 1L
        val freshHlc = hlc.nextStamp(wallClockMs)
        store.coalesceQueueItem(
            queueId = queueItem.queueId,
            newOperation = queueItem.operation,
            newPriority = queueItem.priority,
            newHlc = freshHlc,
            newPayloadJson = queueItem.payloadJson
        )
        store.upsertLocalResource(
            LocalFhirResourceRecord(
                resourceKey = "${queueItem.resourceType}/${queueItem.resourceId}",
                facilityId = queueItem.facilityId,
                resourceType = queueItem.resourceType,
                resourceId = queueItem.resourceId,
                patientId = queueItem.patientId,
                versionId = bumpedVersion,
                etag = "W/\"$bumpedVersion\"",
                hlcTimestamp = freshHlc,
                originNodeId = nodeId,
                baseFhirJson = remotePayloadJson,
                fhirJson = queueItem.payloadJson,
                syncState = "PENDING_SYNC",
                updatedAtMs = wallClockMs
            )
        )
        refreshTelemetry(queueItem.facilityId, wallClockMs)
    }

    private fun acceptRemoteServerWinner(
        queueItem: QueuedFhirSyncItem,
        remotePayloadJson: String,
        remoteHlc: String,
        remoteVersionId: Long,
        wallClockMs: Long
    ) {
        store.markQueueItemSynced(queueItem.queueId)
        applyRemoteResourceLocally(
            facilityId = queueItem.facilityId,
            resourceType = queueItem.resourceType,
            resourceId = queueItem.resourceId,
            operation = "UPSERT",
            remotePayloadJson = remotePayloadJson,
            remoteHlc = remoteHlc,
            nextVersionId = remoteVersionId,
            wallClockMs = wallClockMs
        )
        refreshTelemetry(queueItem.facilityId, wallClockMs)
    }

    private fun applyRemoteResourceLocally(
        facilityId: String,
        resourceType: String,
        resourceId: String,
        operation: String,
        remotePayloadJson: String,
        remoteHlc: String,
        nextVersionId: Long,
        wallClockMs: Long
    ) {
        val key = "$resourceType/$resourceId"
        if (operation.equals("DELETE", ignoreCase = true)) {
            val existing = store.getLocalResource(resourceType, resourceId)
            if (existing != null) {
                store.upsertLocalResource(
                    existing.copy(
                        syncState = "DELETED_TOMBSTONE",
                        hlcTimestamp = remoteHlc,
                        updatedAtMs = wallClockMs
                    )
                )
            }
            if (resourceType == "Patient") {
                store.db.healthCoreOfflineQueries.deleteLocalPatientById(resourceId)
            }
            return
        }

        val parsed = runCatching { json.parseToJsonElement(remotePayloadJson).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
        val patientId = extractPatientIdFromFhirObject(resourceType, resourceId, parsed)

        store.upsertLocalResource(
            LocalFhirResourceRecord(
                resourceKey = key,
                facilityId = facilityId,
                resourceType = resourceType,
                resourceId = resourceId,
                patientId = patientId,
                versionId = nextVersionId,
                etag = "W/\"$nextVersionId\"",
                hlcTimestamp = remoteHlc,
                originNodeId = "server",
                baseFhirJson = remotePayloadJson,
                fhirJson = remotePayloadJson,
                syncState = "SYNCED",
                updatedAtMs = wallClockMs
            )
        )

        if (resourceType == "Patient") {
            store.db.healthCoreOfflineQueries.insertOrReplacePatient(
                id = resourceId,
                facility_id = facilityId,
                mrn = parsed["mrn"]?.jsonPrimitive?.contentOrNull ?: resourceId,
                family_name = parsed["familyName"]?.jsonPrimitive?.contentOrNull ?: "Unknown",
                given_name = parsed["givenName"]?.jsonPrimitive?.contentOrNull ?: "Patient",
                birth_date = parsed["birthDate"]?.jsonPrimitive?.contentOrNull ?: "1970-01-01",
                gender = parsed["gender"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                blood_group = parsed["bloodGroup"]?.jsonPrimitive?.contentOrNull,
                allergies_csv = parsed["allergiesCsv"]?.jsonPrimitive?.contentOrNull,
                hlc_timestamp = remoteHlc
            )
        }
    }

    private fun markLocalResourceSynced(item: QueuedFhirSyncItem, wallClockMs: Long) {
        val existing = store.getLocalResource(item.resourceType, item.resourceId) ?: return
        store.upsertLocalResource(
            existing.copy(
                baseFhirJson = item.payloadJson,
                syncState = if (item.operation == "DELETE") "DELETED_TOMBSTONE" else "SYNCED",
                updatedAtMs = wallClockMs
            )
        )
    }

    private fun scheduleExponentialBackoffRetry(
        item: QueuedFhirSyncItem,
        errorReason: String,
        wallClockMs: Long
    ) {
        val nextAttempt = item.attemptCount + 1L
        if (nextAttempt >= item.maxAttempts) {
            store.scheduleRetry(
                queueId = item.queueId,
                status = "FAILED_PERMANENT",
                attemptCount = nextAttempt,
                nextAttemptAtMs = Long.MAX_VALUE,
                lastError = "Max retry attempts (${item.maxAttempts}) exhausted: $errorReason"
            )
            return
        }
        val multiplier = 2.0.pow(nextAttempt.toDouble()).toLong()
        val backoffDelayMs = min(baseRetryDelayMs * multiplier, maxRetryDelayMs)
        store.scheduleRetry(
            queueId = item.queueId,
            status = "RETRY_SCHEDULED",
            attemptCount = nextAttempt,
            nextAttemptAtMs = wallClockMs + backoffDelayMs,
            lastError = errorReason
        )
    }

    private fun refreshTelemetry(facilityId: String, wallClockMs: Long) {
        val pendingCount = store.selectAllPending(facilityId).size
        val conflictCount = store.listOpenConflicts(facilityId).size
        val cursor = store.currentSyncCursor(facilityId)
        _telemetry.value = _telemetry.value.copy(
            queuedCount = pendingCount,
            openConflictCount = conflictCount,
            lastSyncedSeq = cursor,
            lastSyncWallClockMs = wallClockMs
        )
    }

    private fun enrichFhirJsonWithIdAndMeta(
        original: JsonObject,
        resourceType: String,
        resourceId: String,
        versionId: Long,
        hlcStamp: String
    ): String {
        val updated = LinkedHashMap<String, JsonElement>(original)
        updated["resourceType"] = JsonPrimitive(resourceType)
        updated["id"] = JsonPrimitive(resourceId)
        updated["meta"] = buildJsonObject {
            put("versionId", versionId.toString())
            put("source", "urn:opes:kmp:$nodeId")
            put("hlcTimestamp", hlcStamp)
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(updated))
    }

    private fun mergeJsonObjectsShallow(olderJson: String, newerJson: String): String {
        val oldObj = runCatching { json.parseToJsonElement(olderJson).jsonObject }.getOrNull() ?: return newerJson
        val newObj = runCatching { json.parseToJsonElement(newerJson).jsonObject }.getOrNull() ?: return newerJson
        val merged = LinkedHashMap<String, JsonElement>(oldObj)
        for ((k, v) in newObj) {
            merged[k] = v
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(merged))
    }

    private fun extractPatientIdFromFhirObject(
        resourceType: String,
        resourceId: String,
        obj: JsonObject
    ): String? {
        if (resourceType == "Patient") return resourceId
        obj["patientId"]?.jsonPrimitive?.contentOrNull?.let { return it }
        for (refField in listOf("subject", "patient")) {
            val refElem = obj[refField]
            if (refElem is JsonObject) {
                val refStr = refElem["reference"]?.jsonPrimitive?.contentOrNull
                if (!refStr.isNullOrBlank()) {
                    return refStr.substringAfterLast('/')
                }
            }
        }
        return null
    }
}
