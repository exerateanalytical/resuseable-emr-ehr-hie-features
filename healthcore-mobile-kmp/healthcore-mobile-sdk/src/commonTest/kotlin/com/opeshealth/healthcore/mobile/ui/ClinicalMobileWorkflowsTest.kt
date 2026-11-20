/*
 * =============================================================================
 * Opes HealthCore Mobile SDK — KMP Clinical Safety & Governance Rules Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * =============================================================================
 */
package com.opeshealth.healthcore.mobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClinicalMobileWorkflowsTest {

    @Test
    fun fiveRightsBarcodeVerificationBlocksMismatchesAndAllowsExactMatch() {
        val patientId = "01950001-0000-7000-8000-000000000042"

        val wrongPatient = ClinicalMobileSafetyRules.evaluateFiveRightsBarcode(
            expectedPatientId = patientId,
            expectedDrugCode = "RX-AMOX-500",
            expectedDose = "500 mg PO",
            scannedWristband = "WB:01950001-0000-7000-8000-000000000099",
            scannedDrugBarcode = "RX:RX-AMOX-500",
            doseToAdminister = "500 mg PO"
        )
        assertFalse(wrongPatient.allowed)
        assertTrue(wrongPatient.failureReason!!.contains("Right Patient"))

        val wrongDrug = ClinicalMobileSafetyRules.evaluateFiveRightsBarcode(
            expectedPatientId = patientId,
            expectedDrugCode = "RX-AMOX-500",
            expectedDose = "500 mg PO",
            scannedWristband = "WB:$patientId",
            scannedDrugBarcode = "RX:RX-WRONG-DRUG",
            doseToAdminister = "500 mg PO"
        )
        assertFalse(wrongDrug.allowed)
        assertTrue(wrongDrug.failureReason!!.contains("Right Medication"))

        val wrongDose = ClinicalMobileSafetyRules.evaluateFiveRightsBarcode(
            expectedPatientId = patientId,
            expectedDrugCode = "RX-AMOX-500",
            expectedDose = "500 mg PO",
            scannedWristband = "WB:$patientId",
            scannedDrugBarcode = "RX:RX-AMOX-500",
            doseToAdminister = "1000 mg PO"
        )
        assertFalse(wrongDose.allowed)
        assertTrue(wrongDose.failureReason!!.contains("Right Dose"))

        val valid = ClinicalMobileSafetyRules.evaluateFiveRightsBarcode(
            expectedPatientId = patientId,
            expectedDrugCode = "RX-AMOX-500",
            expectedDose = "500 mg PO",
            scannedWristband = "WB:$patientId",
            scannedDrugBarcode = "RX:RX-AMOX-500",
            doseToAdminister = "500 mg PO"
        )
        assertTrue(valid.allowed)
    }

    @Test
    fun news2EscalationBandsClassifyClinicalRiskAccurately() {
        assertTrue(ClinicalMobileSafetyRules.classifyNews2EscalationBand(0).startsWith("BASELINE"))
        assertTrue(ClinicalMobileSafetyRules.classifyNews2EscalationBand(3).startsWith("LOW"))
        assertTrue(ClinicalMobileSafetyRules.classifyNews2EscalationBand(5).startsWith("MEDIUM"))
        assertTrue(ClinicalMobileSafetyRules.classifyNews2EscalationBand(8).startsWith("HIGH"))
    }

    @Test
    fun remoteWipeAndRevokedStatusTriggerFailClosedLockout() {
        assertFalse(ClinicalMobileSafetyRules.shouldEnforceRemoteWipeLockout("ACTIVE", false))
        assertTrue(ClinicalMobileSafetyRules.shouldEnforceRemoteWipeLockout("ACTIVE", true))
        assertTrue(ClinicalMobileSafetyRules.shouldEnforceRemoteWipeLockout("REVOKED", false))
        assertTrue(ClinicalMobileSafetyRules.shouldEnforceRemoteWipeLockout("WIPED", false))
        assertEquals(true, ClinicalMobileSafetyRules.shouldEnforceRemoteWipeLockout("revoked", false))
    }
}
