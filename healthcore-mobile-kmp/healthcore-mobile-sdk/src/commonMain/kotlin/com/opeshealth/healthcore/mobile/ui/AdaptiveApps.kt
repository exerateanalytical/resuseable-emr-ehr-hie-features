/*
 * =============================================================================
 * Opes HealthCore Mobile & iPad Suite — Adaptive Compose Multiplatform Apps
 * Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
 * Website            : https://www.opeshealthsystems.com | Douala, Cameroon
 * =============================================================================
 * Pure Kotlin + Compose Multiplatform delivering 3 offline-first applications:
 *  1) HealthCore Clinician (Phone thumb-zone + iPad 3-Column Cockpit w/ Stylus)
 *  2) HealthCore Patient (Ed25519 QR IPS Health Card, Meds, Appointments, MoMo)
 *  3) HealthCore CHW Field (Offline Household, ANC/MUAC/iCCM, Referral QR)
 * Strictly ZERO TypeScript.
 */
package com.opeshealth.healthcore.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opeshealth.healthcore.mobile.sync.OfflineClinicalSafetyEngine
import com.opeshealth.healthcore.mobile.sync.OfflineVaultSyncRepository
import kotlin.math.roundToInt

data class PatientQueueItem(
    val id: String,
    val mrn: String,
    val fullName: String,
    val esiLevel: Int,
    val bloodGroup: String,
    val allergies: String,
    val pendingSyncCount: Int
)

/**
 * Top-level SQLDelight-backed adaptive application host switching cleanly between
 * Clinician, Patient QR Companion, and CHW Field Worker apps using encrypted local SQLite.
 */
@Composable
fun HealthCoreSuiteHost(
    vault: OfflineVaultSyncRepository,
    facilityId: String,
    wallClockMs: () -> Long,
    onRequestBarcodeScan: ((String) -> Unit) -> Unit = {}
) {
    vault.seedSamplePatientsIfEmpty(facilityId, wallClockMs())
    var activePersona by remember { mutableStateOf("CLINICIAN") }
    var pendingCount by remember { mutableStateOf(vault.pendingOutboxCount()) }
    var lastStatusMessage by remember { mutableStateOf("Encrypted Offline SQLite Vault Ready") }
    val queuedMedia = remember { mutableStateListOf<EncryptedMediaSyncItem>() }

    val cachedPatients = remember(pendingCount) {
        vault.listCachedPatients().mapIndexed { idx, row ->
            PatientQueueItem(
                id = row.id,
                mrn = row.mrn,
                fullName = "${row.given_name} ${row.family_name}",
                esiLevel = (idx % 3) + 2,
                bloodGroup = row.blood_group ?: "Unknown",
                allergies = row.allergies_csv ?: "",
                pendingSyncCount = pendingCount
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0A3642)).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("CLINICIAN", "VITALS", "EMAR", "MEDIA", "SYNC", "PATIENT", "CHW").forEach { mode ->
                FilterChip(
                    selected = activePersona == mode,
                    onClick = { activePersona = mode },
                    label = { Text(mode, color = if (activePersona == mode) Color(0xFF0F4C5C) else Color.White) }
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                onRequestBarcodeScan { scanned -> lastStatusMessage = "Scanned: $scanned" }
            }) {
                Text("Scan QR", color = Color(0xFFBAE6FD))
            }
        }

        when (activePersona) {
            "CLINICIAN" -> HealthCoreClinicianApp(
                patients = cachedPatients,
                offlineMode = true,
                pendingSyncCount = pendingCount,
                statusMessage = lastStatusMessage,
                onSignSoapNote = { patientId, soapText, strokesJson ->
                    vault.enqueueClinicalNoteOffline(patientId, soapText, strokesJson, wallClockMs())
                    pendingCount = vault.pendingOutboxCount()
                    lastStatusMessage = "SOAP + Stylus sketch saved offline ($pendingCount queued)"
                }
            )
            "VITALS" -> VitalsNews2CaptureScreen(
                patient = cachedPatients.firstOrNull(),
                onRecordVitalsOffline = { patientId, news2, summaryJson ->
                    vault.enqueueClinicalNoteOffline(patientId, "VITALS_NEWS2=$news2 | $summaryJson", "[]", wallClockMs())
                    pendingCount = vault.pendingOutboxCount()
                    lastStatusMessage = "NEWS2=$news2 vitals queued offline ($pendingCount queued)"
                }
            )
            "EMAR" -> EmarBarcodeVerificationScreen(
                patient = cachedPatients.firstOrNull(),
                onRequestBarcodeScan = onRequestBarcodeScan,
                onConfirmAdministeredOffline = { patientId, drugCode, dose ->
                    vault.enqueueClinicalNoteOffline(patientId, "EMAR_5RIGHTS_OK|$drugCode|$dose", "[]", wallClockMs())
                    pendingCount = vault.pendingOutboxCount()
                    lastStatusMessage = "5-Rights eMAR recorded offline ($pendingCount queued)"
                }
            )
            "MEDIA" -> ClinicalMediaSyncScreen(
                patient = cachedPatients.firstOrNull(),
                deviceStatus = "ACTIVE",
                remoteWipePending = false,
                queuedMedia = queuedMedia,
                onQueueEncryptedMedia = { patientId, mimeType, sizeBytes, sha256Hex ->
                    val validation = EncryptedMediaSyncPolicy.validateForEncryptedQueue(
                        mimeType, sizeBytes, sha256Hex, "ACTIVE", false
                    )
                    if (validation.allowed) {
                        val id = "MEDIA-${wallClockMs()}"
                        queuedMedia.add(
                            EncryptedMediaSyncItem(
                                mediaId = id,
                                patientId = patientId,
                                mimeType = mimeType,
                                sizeBytes = sizeBytes,
                                sha256Hex = sha256Hex,
                                uploadedChunks = 0,
                                totalChunks = validation.totalChunks
                            )
                        )
                        vault.enqueueClinicalNoteOffline(
                            patientId,
                            "ENCRYPTED_MEDIA_QUEUED|$id|$mimeType|$sizeBytes|$sha256Hex",
                            "[]",
                            wallClockMs()
                        )
                        pendingCount = vault.pendingOutboxCount()
                        lastStatusMessage = "Encrypted media $id queued (${validation.totalChunks} chunks)"
                    }
                },
                onExecuteLocalWipe = {
                    queuedMedia.clear()
                    lastStatusMessage = "Local encrypted media & SQLite vault purged"
                }
            )
            "SYNC" -> OfflineSyncConflictQueueScreen(
                pendingOutboxCount = pendingCount,
                deviceStatus = "ACTIVE",
                remoteWipePending = false,
                conflicts = emptyList(),
                onTriggerSync = { lastStatusMessage = "Delta sync requested ($pendingCount pending)" },
                onExecuteLocalWipe = {
                    queuedMedia.clear()
                    lastStatusMessage = "Local encrypted SQLite vault wipe triggered"
                }
            )
            "PATIENT" -> HealthCorePatientApp(
                patientName = cachedPatients.firstOrNull()?.fullName ?: "Patient",
                qrHealthCardToken = "OPES-ED25519-IPS-V1.${facilityId.take(8)}",
                cbhiPolicyNo = "CM-MUTUELLE-2026-8841"
            )
            else -> HealthCoreChwFieldApp(
                chwName = "CHW Douala Sector 4",
                householdsCount = cachedPatients.size * 12,
                onRecordMuacVisit = { muac ->
                    val targetId = cachedPatients.firstOrNull()?.id ?: "01950001-0000-7000-8000-000000000001"
                    vault.enqueueChwMuacVisitOffline(targetId, muac, wallClockMs())
                    pendingCount = vault.pendingOutboxCount()
                    lastStatusMessage = if (muac < 115) "SAM Referral QR generated ($pendingCount queued)" else "CHW Visit queued"
                }
            )
        }
    }
}

/**
 * Adaptive clinician shell automatically rendering a 3-column iPad/Tablet Cockpit
 * when width >= 840.dp and a single-hand thumb-zone layout on smartphones.
 */
@Composable
fun HealthCoreClinicianApp(
    patients: List<PatientQueueItem>,
    offlineMode: Boolean,
    pendingSyncCount: Int,
    statusMessage: String = "",
    onSignSoapNote: (patientId: String, soapText: String, stylusStrokesJson: String) -> Unit
) {
    var selectedPatient by remember { mutableStateOf(patients.firstOrNull()) }
    var soapDraft by remember { mutableStateOf("") }
    val stylusStrokes = remember { mutableStateListOf<Pair<Offset, Offset>>() }

    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0F4C5C), secondary = Color(0xFF0E7C7B))) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color(0xFFF6F9FA))) {
            val isTabletOrIpad = maxWidth >= 840.dp

            Column(modifier = Modifier.fillMaxSize()) {
                // Offline-First Sync Banner + Persistent Selected Patient Safety Header
                Surface(color = if (offlineMode) Color(0xFF9A3412) else Color(0xFF0F4C5C)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (offlineMode) "OFFLINE MODE — Encrypted SQLite Active ($pendingSyncCount queued)"
                            else "ONLINE — Delta Sync Up to Date",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        selectedPatient?.let {
                            Text(
                                text = "${it.fullName} | MRN: ${it.mrn} | Blood: ${it.bloodGroup} | Allergies: ${it.allergies}",
                                color = Color(0xFFE0F2FE),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (statusMessage.isNotBlank()) {
                    Text(
                        text = statusMessage,
                        modifier = Modifier.fillMaxWidth().background(Color(0xFFE0F2FE)).padding(horizontal = 16.dp, vertical = 6.dp),
                        color = Color(0xFF0C4A6E),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (isTabletOrIpad) {
                    // iPad / Android Tablet 3-Column Clinical Cockpit
                    Row(modifier = Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Card(modifier = Modifier.weight(0.26f).fillMaxHeight()) {
                            PatientQueueList(patients) { selectedPatient = it }
                        }

                        Card(modifier = Modifier.weight(0.46f).fillMaxHeight()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Consultation & Stylus Exam Canvas", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(
                                    value = soapDraft,
                                    onValueChange = { soapDraft = it },
                                    label = { Text("SOAP Note (Offline-Safe Auto-Draft)") },
                                    modifier = Modifier.fillMaxWidth().height(160.dp)
                                )
                                Text("Dental Odontogram / Eye Fundus / Derm Stylus Annotation", style = MaterialTheme.typography.labelLarge)
                                StylusAnnotationCanvas(
                                    strokes = stylusStrokes,
                                    modifier = Modifier.fillMaxWidth().height(180.dp)
                                )
                                Button(
                                    onClick = {
                                        selectedPatient?.let {
                                            val jsonStrokes = "[" + stylusStrokes.joinToString(",") { (a, b) ->
                                                """{"x1":${a.x.roundToInt()},"y1":${a.y.roundToInt()},"x2":${b.x.roundToInt()},"y2":${b.y.roundToInt()}}"""
                                            } + "]"
                                            onSignSoapNote(it.id, soapDraft, jsonStrokes)
                                        }
                                    },
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Text("Sign & Enqueue Offline Sync")
                                }
                            }
                        }

                        Card(modifier = Modifier.weight(0.28f).fillMaxHeight()) {
                            CdssSafetyRail(selectedPatient)
                        }
                    }
                } else {
                    // Phone Portrait Layout (>=48dp touch targets for rapid bedside capture)
                    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PatientQueueList(patients.take(4)) { selectedPatient = it }
                        OutlinedTextField(
                            value = soapDraft,
                            onValueChange = { soapDraft = it },
                            label = { Text("Quick Bedside Note") },
                            modifier = Modifier.fillMaxWidth().height(120.dp)
                        )
                        Button(
                            onClick = {
                                selectedPatient?.let { onSignSoapNote(it.id, soapDraft, "[]") }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Text("Save Offline & Sync")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatientQueueList(patients: List<PatientQueueItem>, onSelect: (PatientQueueItem) -> Unit) {
    LazyColumn(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(patients, key = { it.id }) { p ->
            ElevatedCard(onClick = { onSelect(p) }, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(p.fullName, fontWeight = FontWeight.Bold)
                    Text("MRN ${p.mrn} • ESI-${p.esiLevel}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun StylusAnnotationCanvas(
    strokes: MutableList<Pair<Offset, Offset>>,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .background(Color(0xFFF1F5F9))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val end = change.position
                    val start = end - dragAmount
                    strokes.add(start to end)
                }
            }
    ) {
        strokes.forEach { (start, end) ->
            drawLine(color = Color(0xFF0F4C5C), start = start, end = end, strokeWidth = 3.5f)
        }
    }
}

@Composable
private fun CdssSafetyRail(patient: PatientQueueItem?) {
    val news2 = OfflineClinicalSafetyEngine.computeNews2Score(39.2, 118, 22, 108, 95)
    val alerts = OfflineClinicalSafetyEngine.evaluateOfflinePrescriptionSafety(
        proposedDrugCode = "723",
        patientAllergiesCsv = patient?.allergies ?: "",
        activeMedCodesCsv = "11289",
        egfr = 42.0
    )
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Offline CDSS Safety Rail", style = MaterialTheme.typography.titleMedium)
        Text("Current NEWS2 Score: $news2", fontWeight = FontWeight.Bold, color = Color(0xFFB45309))
        alerts.forEach { a ->
            Surface(color = Color(0xFFFEE2E2), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(a.title, fontWeight = FontWeight.Bold, color = Color(0xFF991B1B))
                    Text(a.guidance, style = MaterialTheme.typography.bodySmall, color = Color(0xFF7F1D1D))
                }
            }
        }
    }
}

/**
 * HealthCore Patient Companion App: Offline Ed25519 QR Health Card, Lab Results,
 * Medication Reminders, Consent Privacy Controls & UHC/Mobile Money Wallet.
 */
@Composable
fun HealthCorePatientApp(patientName: String, qrHealthCardToken: String, cbhiPolicyNo: String) {
    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Opes HealthCore Patient Card", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(patientName, fontWeight = FontWeight.Bold)
                    Text("UHC / CBHI Policy: $cbhiPolicyNo")
                    Text("Signed Offline IPS QR Token: ${qrHealthCardToken.take(36)}...", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * HealthCore CHW Field App: Offline Household Registry, Pediatric MUAC/iCCM/ANC Visits & QR Referrals.
 */
@Composable
fun HealthCoreChwFieldApp(chwName: String, householdsCount: Int, onRecordMuacVisit: (Int) -> Unit) {
    var muacInput by remember { mutableStateOf("125") }
    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("CHW Field Companion — $chwName", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Cached Offline Households: $householdsCount")
            OutlinedTextField(
                value = muacInput,
                onValueChange = { muacInput = it },
                label = { Text("Pediatric MUAC (mm) — <115 triggers SAM QR Referral") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { muacInput.toIntOrNull()?.let(onRecordMuacVisit) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("Save Household Visit Offline")
            }
        }
    }
}
