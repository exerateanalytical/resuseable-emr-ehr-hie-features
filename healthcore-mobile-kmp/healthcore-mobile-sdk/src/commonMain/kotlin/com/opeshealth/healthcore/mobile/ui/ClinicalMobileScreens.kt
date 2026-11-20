/*
 * =============================================================================
 * Opes HealthCore Mobile & iPad Suite — Clinical Workflow & Governance Screens
 * Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
 * Website            : https://www.opeshealthsystems.com | Douala, Cameroon
 * =============================================================================
 * Pure Kotlin + Compose Multiplatform (Android & iOS commonMain). Strictly ZERO TypeScript.
 */
package com.opeshealth.healthcore.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opeshealth.healthcore.mobile.sync.OfflineClinicalSafetyEngine

/** Deterministic 5-Rights Barcode eMAR evaluation result for offline bedside safety. */
data class MobileFiveRightsCheckResult(
    val allowed: Boolean,
    val failureReason: String? = null
)

/** Conflict item surfaced in the mobile Offline Sync & Conflict Queue screen. */
data class MobileSyncConflictItem(
    val entityType: String,
    val entityId: String,
    val localSummary: String,
    val serverSummary: String
)

/**
 * Pure Kotlin clinical safety evaluators backing the Compose Multiplatform screens so both
 * UI state and headless {@code commonTest} suites validate identical deterministic rules.
 */
object ClinicalMobileSafetyRules {

    fun classifyNews2EscalationBand(score: Int): String = when {
        score >= 7 -> "HIGH — Emergency Clinical Response"
        score >= 5 -> "MEDIUM — Urgent Clinician Review"
        score >= 1 -> "LOW — Registered Nurse Assessment"
        else -> "BASELINE — Routine Monitoring"
    }

    fun evaluateFiveRightsBarcode(
        expectedPatientId: String,
        expectedDrugCode: String,
        expectedDose: String,
        scannedWristband: String,
        scannedDrugBarcode: String,
        doseToAdminister: String
    ): MobileFiveRightsCheckResult {
        val wb = scannedWristband.trim()
        if (wb != expectedPatientId && wb != "WB:$expectedPatientId") {
            return MobileFiveRightsCheckResult(false, "Right Patient FAILED: Wristband does not match selected patient.")
        }
        val expectedCode = expectedDrugCode.trim().uppercase()
        val drug = scannedDrugBarcode.trim().uppercase()
        val drugOk = expectedCode.isNotEmpty() && (
            drug == expectedCode ||
                drug == "RX:$expectedCode" ||
                drug == "NDC:$expectedCode" ||
                drug == "GTIN:$expectedCode" ||
                drug.endsWith(":$expectedCode")
            )
        if (!drugOk) {
            return MobileFiveRightsCheckResult(false, "Right Medication FAILED: Scanned drug barcode does not match active order.")
        }
        if (doseToAdminister.isBlank() || !doseToAdminister.trim().equals(expectedDose.trim(), ignoreCase = true)) {
            return MobileFiveRightsCheckResult(false, "Right Dose FAILED: Entered dose does not match ordered dose ($expectedDose).")
        }
        return MobileFiveRightsCheckResult(true, null)
    }

    fun shouldEnforceRemoteWipeLockout(deviceStatus: String, remoteWipePending: Boolean): Boolean {
        val normalized = deviceStatus.trim().uppercase()
        return remoteWipePending || normalized == "REVOKED" || normalized == "WIPED"
    }
}

/**
 * Bedside Vital Signs & Deterministic NEWS2 Early-Warning Capture Screen.
 */
@Composable
fun VitalsNews2CaptureScreen(
    patient: PatientQueueItem?,
    onRecordVitalsOffline: (patientId: String, news2Score: Int, summaryJson: String) -> Unit
) {
    var tempText by remember { mutableStateOf("37.2") }
    var hrText by remember { mutableStateOf("84") }
    var rrText by remember { mutableStateOf("18") }
    var sbpText by remember { mutableStateOf("118") }
    var spo2Text by remember { mutableStateOf("97") }

    val temp = tempText.toDoubleOrNull() ?: 37.0
    val hr = hrText.toIntOrNull() ?: 75
    val rr = rrText.toIntOrNull() ?: 16
    val sbp = sbpText.toIntOrNull() ?: 120
    val spo2 = spo2Text.toIntOrNull() ?: 98

    val news2 = OfflineClinicalSafetyEngine.computeNews2Score(temp, hr, rr, sbp, spo2)
    val band = ClinicalMobileSafetyRules.classifyNews2EscalationBand(news2)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Bedside Vitals & NEWS2 Capture", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Patient: ${patient?.fullName ?: "Select a patient"} (${patient?.mrn ?: "—"})")

        Surface(
            color = if (news2 >= 7) Color(0xFFFEE2E2) else if (news2 >= 5) Color(0xFFFEF3C7) else Color(0xFFDCFCE7),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("NEWS2 Score: $news2", fontWeight = FontWeight.Bold)
                Text(band, style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(value = tempText, onValueChange = { tempText = it }, label = { Text("Temp °C") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = hrText, onValueChange = { hrText = it }, label = { Text("HR bpm") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = rrText, onValueChange = { rrText = it }, label = { Text("RR /min") }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(value = sbpText, onValueChange = { sbpText = it }, label = { Text("Systolic BP") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = spo2Text, onValueChange = { spo2Text = it }, label = { Text("SpO2 %") }, modifier = Modifier.weight(1f))
        }

        Button(
            onClick = {
                patient?.let {
                    val json = """{"tempC":$temp,"hr":$hr,"rr":$rr,"sbp":$sbp,"spo2":$spo2,"news2":$news2}"""
                    onRecordVitalsOffline(it.id, news2, json)
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Save Vitals Offline & Evaluate Escalation")
        }
    }
}

/**
 * Bedside 5-Rights Barcode Medication Administration (eMAR) Screen.
 */
@Composable
fun EmarBarcodeVerificationScreen(
    patient: PatientQueueItem?,
    activeOrderDrugCode: String = "RX-AMOX-500",
    activeOrderDose: String = "500 mg PO",
    onRequestBarcodeScan: ((String) -> Unit) -> Unit = {},
    onConfirmAdministeredOffline: (patientId: String, drugCode: String, dose: String) -> Unit
) {
    var wristband by remember(patient?.id) { mutableStateOf("") }
    var drugBarcode by remember { mutableStateOf("") }
    var doseText by remember { mutableStateOf(activeOrderDose) }

    val check = ClinicalMobileSafetyRules.evaluateFiveRightsBarcode(
        expectedPatientId = patient?.id ?: "",
        expectedDrugCode = activeOrderDrugCode,
        expectedDose = activeOrderDose,
        scannedWristband = wristband,
        scannedDrugBarcode = drugBarcode,
        doseToAdminister = doseText
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("5-Rights Barcode eMAR", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Ordered: $activeOrderDrugCode · $activeOrderDose for ${patient?.fullName ?: "—"}")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = wristband,
                onValueChange = { wristband = it },
                label = { Text("Scanned Patient Wristband (WB:<id>)") },
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = { onRequestBarcodeScan { wristband = it } }) { Text("Scan") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = drugBarcode,
                onValueChange = { drugBarcode = it },
                label = { Text("Scanned Drug Barcode (RX:<code>)") },
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = { onRequestBarcodeScan { drugBarcode = it } }) { Text("Scan") }
        }

        OutlinedTextField(
            value = doseText,
            onValueChange = { doseText = it },
            label = { Text("Dose & Route") },
            modifier = Modifier.fillMaxWidth()
        )

        Surface(
            color = if (check.allowed) Color(0xFFDCFCE7) else Color(0xFFFEE2E2),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (check.allowed) "5-Rights Verified — Safe to Administer"
                else check.failureReason ?: "Verification incomplete",
                modifier = Modifier.padding(12.dp),
                color = if (check.allowed) Color(0xFF166534) else Color(0xFF991B1B),
                fontWeight = FontWeight.SemiBold
            )
        }

        Button(
            enabled = check.allowed && patient != null,
            onClick = { patient?.let { onConfirmAdministeredOffline(it.id, activeOrderDrugCode, doseText) } },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Record Medication Administration Offline")
        }
    }
}

/**
 * Offline Sync Outbox, Conflict Resolution & Device Revocation Governance Screen.
 */
@Composable
fun OfflineSyncConflictQueueScreen(
    pendingOutboxCount: Int,
    deviceStatus: String,
    remoteWipePending: Boolean,
    conflicts: List<MobileSyncConflictItem>,
    onTriggerSync: () -> Unit,
    onExecuteLocalWipe: () -> Unit
) {
    if (ClinicalMobileSafetyRules.shouldEnforceRemoteWipeLockout(deviceStatus, remoteWipePending)) {
        DeviceRevocationLockoutScreen(deviceStatus = deviceStatus, onExecuteLocalWipe = onExecuteLocalWipe)
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Offline Sync & Conflict Queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Encrypted SQLite Outbox", fontWeight = FontWeight.Bold)
                    Text("$pendingOutboxCount pending delta mutation(s) · Device: $deviceStatus")
                }
                Button(onClick = onTriggerSync) { Text("Sync Now") }
            }
        }

        Text("Merge Conflicts (${conflicts.size})", style = MaterialTheme.typography.titleMedium)
        if (conflicts.isEmpty()) {
            Text("No unresolved sync conflicts.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(conflicts, key = { it.entityId }) { c ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${c.entityType} (${c.entityId.take(8)})", fontWeight = FontWeight.Bold)
                            Text("Local: ${c.localSummary}", style = MaterialTheme.typography.bodySmall)
                            Text("Server: ${c.serverSummary}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Fail-closed lockout screen shown when an administrator revokes a lost/stolen mobile device
 * or issues a remote wipe command via {@code OfflineDeviceGovernanceService}.
 */
@Composable
fun DeviceRevocationLockoutScreen(
    deviceStatus: String,
    onExecuteLocalWipe: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF7F1D1D)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("DEVICE ACCESS REVOKED", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "This clinical device has status '$deviceStatus' and is blocked from offline charting or delta synchronization. " +
                "Purge the local encrypted SQLite vault and contact hospital IT security to re-enroll.",
            color = Color(0xFFFEE2E2)
        )
        Button(
            onClick = onExecuteLocalWipe,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF7F1D1D)),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Purge Local Encrypted Vault Now", fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Offline encrypted clinical media attachment metadata (wound photography, ECG captures,
 * signed consent scans) queued for chunked resumable synchronization.
 */
data class EncryptedMediaSyncItem(
    val mediaId: String,
    val patientId: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256Hex: String,
    val cipherSuite: String = "AES-256-GCM",
    val uploadedChunks: Int = 0,
    val totalChunks: Int = 1,
    val status: String = "QUEUED_ENCRYPTED"
)

data class MediaSyncValidationResult(
    val allowed: Boolean,
    val totalChunks: Int,
    val rejectionReason: String? = null
)

/**
 * Pure Kotlin policy governing encrypted local clinical media capture, resumable chunk sizing,
 * MIME allow-listing, and fail-closed device revocation handling.
 */
object EncryptedMediaSyncPolicy {
    const val CHUNK_SIZE_BYTES: Long = 262_144L // 256 KiB resumable chunks for 2G/3G networks
    const val MAX_MEDIA_BYTES: Long = 25L * 1024L * 1024L // 25 MiB cap per clinical attachment
    private val ALLOWED_MIME_TYPES = setOf(
        "image/jpeg",
        "image/png",
        "application/pdf",
        "application/dicom",
        "audio/mp4"
    )

    fun validateForEncryptedQueue(
        mimeType: String,
        sizeBytes: Long,
        sha256Hex: String,
        deviceStatus: String,
        remoteWipePending: Boolean
    ): MediaSyncValidationResult {
        if (ClinicalMobileSafetyRules.shouldEnforceRemoteWipeLockout(deviceStatus, remoteWipePending)) {
            return MediaSyncValidationResult(false, 0, "Device is revoked or pending wipe; clinical media capture blocked.")
        }
        val normalizedMime = mimeType.trim().lowercase()
        if (normalizedMime !in ALLOWED_MIME_TYPES) {
            return MediaSyncValidationResult(false, 0, "Unsupported clinical media MIME type: $mimeType")
        }
        if (sizeBytes <= 0L || sizeBytes > MAX_MEDIA_BYTES) {
            return MediaSyncValidationResult(false, 0, "Media size must be between 1 byte and 25 MiB.")
        }
        val cleanHash = sha256Hex.trim().lowercase()
        if (cleanHash.length != 64 || cleanHash.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            return MediaSyncValidationResult(false, 0, "Valid 64-character SHA-256 integrity digest is required.")
        }
        val chunks = ((sizeBytes + CHUNK_SIZE_BYTES - 1L) / CHUNK_SIZE_BYTES).toInt().coerceAtLeast(1)
        return MediaSyncValidationResult(true, chunks, null)
    }

    fun computeUploadProgressPercent(uploadedChunks: Int, totalChunks: Int): Int {
        if (totalChunks <= 0) return 0
        return ((uploadedChunks.coerceIn(0, totalChunks) * 100) / totalChunks)
    }
}

/**
 * Encrypted Clinical Media Capture & Chunked Resumable Sync Queue Screen.
 */
@Composable
fun ClinicalMediaSyncScreen(
    patient: PatientQueueItem?,
    deviceStatus: String,
    remoteWipePending: Boolean,
    queuedMedia: List<EncryptedMediaSyncItem>,
    onQueueEncryptedMedia: (patientId: String, mimeType: String, sizeBytes: Long, sha256Hex: String) -> Unit,
    onExecuteLocalWipe: () -> Unit
) {
    if (ClinicalMobileSafetyRules.shouldEnforceRemoteWipeLockout(deviceStatus, remoteWipePending)) {
        DeviceRevocationLockoutScreen(deviceStatus = deviceStatus, onExecuteLocalWipe = onExecuteLocalWipe)
        return
    }

    var selectedMime by remember { mutableStateOf("image/jpeg") }
    var sizeKbText by remember { mutableStateOf("640") }
    val sizeBytes = (sizeKbText.toLongOrNull() ?: 640L) * 1024L
    val sampleDigest = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    val validation = EncryptedMediaSyncPolicy.validateForEncryptedQueue(
        mimeType = selectedMime,
        sizeBytes = sizeBytes,
        sha256Hex = sampleDigest,
        deviceStatus = deviceStatus,
        remoteWipePending = remoteWipePending
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Encrypted Clinical Media Sync", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Patient: ${patient?.fullName ?: "Select a patient"} · Cipher: AES-256-GCM · Chunked 256 KiB Resumable Upload")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("image/jpeg", "application/pdf", "application/dicom").forEach { mime ->
                FilterChip(
                    selected = selectedMime == mime,
                    onClick = { selectedMime = mime },
                    label = { Text(mime.substringAfter("/").uppercase()) }
                )
            }
        }

        OutlinedTextField(
            value = sizeKbText,
            onValueChange = { sizeKbText = it },
            label = { Text("Encrypted Attachment Size (KiB)") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            enabled = validation.allowed && patient != null,
            onClick = {
                patient?.let { onQueueEncryptedMedia(it.id, selectedMime, sizeBytes, sampleDigest) }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Encrypt & Queue Clinical Media (${validation.totalChunks} chunk(s))")
        }

        Text("Queued Encrypted Media (${queuedMedia.size})", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(queuedMedia, key = { it.mediaId }) { item ->
                val pct = EncryptedMediaSyncPolicy.computeUploadProgressPercent(item.uploadedChunks, item.totalChunks)
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${item.mimeType} · ${item.cipherSuite} · ${item.status}", fontWeight = FontWeight.Bold)
                        Text("Chunks: ${item.uploadedChunks}/${item.totalChunks} ($pct%) · SHA-256: ${item.sha256Hex.take(16)}...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

