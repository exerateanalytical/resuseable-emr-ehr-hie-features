package com.example.healthcore.emr;

import com.opeshealth.healthcore.emr.domain.EmrEntities.*;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService;
import com.opeshealth.healthcore.security.rbac.WorkforceCredentialSpi;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent Spring Boot consumer on real PostgreSQL 16 with Liquibase.
 * Covers encounter, triage, NEWS2, signed SOAP revision and revisits.
 * Not a full consultation/order/result/discharge or live HTTP/OIDC acceptance.
 */
@Tag("workflow-it")
@Testcontainers
@SpringBootTest(classes = OutpatientEmrApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IndependentWorkflowIT.SyntheticSecurityFixture.class)
class IndependentWorkflowIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.liquibase.enabled", () -> "true");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SyntheticSecurityFixture {
        @Bean
        JwtDecoder rejectAllJwtDecoder() {
            // Domain workflow test does not forge authenticated HTTP requests.
            return token -> { throw new JwtException("Only separately verified HTTP/JWT tests may authenticate"); };
        }

        @Bean
        WorkforceCredentialSpi testPractitionerCredentials() {
            return (facility, practitioner) ->
                    facility != null && "dr-synthetic-licensed".equals(practitioner);
        }
    }

    @Autowired
    private EmrClinicalWorkflowService clinical;

    @AfterEach
    void clearFacilityContext() {
        FacilityContext.clear();
    }

    @Test
    void persistsTwoVisitsTriageMeasuredNews2AndSignedSoapNoteAcrossConnections() throws Exception {
        UUID facility = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        FacilityContext.setFacilityId(facility);
        EncounterEntity first = clinical.startEncounter(facility, patient, "AMB", "OPD-GENERAL");
        assertEquals("IN_PROGRESS", first.getStatus());

        TriageAssessmentEntity triage = new TriageAssessmentEntity();
        triage.setEncounterId(first.getId());
        triage.setEsiLevel(3);
        triage.setChiefComplaint("Synthetic persistent cough");
        triage.setTriagedBy("nurse-synthetic");
        clinical.recordTriage(triage);

        VitalSignsEntity measurements = new VitalSignsEntity();
        measurements.setEncounterId(first.getId());
        measurements.setPatientId(patient);
        measurements.setRespRatePerMin(16);
        measurements.setSpo2Percent(98);
        measurements.setSystolicBp(120);
        measurements.setHeartRateBpm(72);
        measurements.setTempCelsius(new BigDecimal("37.0"));
        Map<String, Object> vitalsRecorded = clinical.recordVitalsAndCheckPanic(measurements);
        assertEquals(Boolean.TRUE, vitalsRecorded.get("news2Complete"));
        assertEquals(0, ((VitalSignsEntity) vitalsRecorded.get("vitals")).getNews2Score());

        ClinicalNoteRevisionEntity soap = new ClinicalNoteRevisionEntity();
        soap.setEncounterId(first.getId());
        soap.setPatientId(patient);
        soap.setNoteType("SOAP");
        soap.setAuthorId("dr-synthetic-licensed");
        soap.setSubjective("Synthetic persistent cough for three days");
        soap.setObjective("Normal synthetic adult vitals");
        soap.setAssessment("Synthetic encounter for workflow testing only");
        soap.setPlanText("Review and follow-up");
        ClinicalNoteRevisionEntity signed = clinical.signOrAmendNote(soap, true);
        assertEquals("SIGNED", signed.getStatus());
        assertNotNull(signed.getSignedAt());
        signed.setPlanText("Unsafe attempted edit of signed note");
        assertThrows(IllegalStateException.class, () -> clinical.signOrAmendNote(signed, false));

        ClinicalNoteRevisionEntity addendum = new ClinicalNoteRevisionEntity();
        addendum.setEncounterId(first.getId());
        addendum.setPatientId(patient);
        addendum.setAuthorId("dr-synthetic-licensed");
        addendum.setNoteType("SOAP");
        addendum.setParentNoteId(signed.getId());
        addendum.setAssessment("Synthetic revised assessment");
        ClinicalNoteRevisionEntity corrected = clinical.signOrAmendNote(addendum, true);
        assertEquals("ADDENDUM", corrected.getNoteType());
        assertEquals(2, corrected.getRevisionNumber());

        EncounterEntity revisit = clinical.startEncounter(facility, patient, "AMB", "OPD-GENERAL");
        assertNotEquals(first.getId(), revisit.getId());
        assertEquals(2L, countPatientEncounters(facility, patient));
        assertEquals(1L, countForEncounter("hc_triage_assessments", first.getId()));
        assertEquals(1L, countForEncounter("hc_vital_signs", first.getId()));
        assertEquals(2L, countForEncounter("hc_clinical_notes", first.getId()));
        assertEquals("SIGNED", readNoteStatus(facility, patient, first.getId()));
    }

    @Test
    void rejectsForeignFacilityAndExpiredCredentialsAndNeverInventsNormalNews2() throws Exception {
        UUID facility = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        assertThrows(SecurityException.class,
                () -> clinical.startEncounter(facility, patient, "AMB", "OPD"));
        FacilityContext.setFacilityId(facility);
        EncounterEntity encounter = clinical.startEncounter(facility, patient, "AMB", "OPD");

        TriageAssessmentEntity invalid = new TriageAssessmentEntity();
        invalid.setEncounterId(encounter.getId());
        invalid.setEsiLevel(9);
        invalid.setChiefComplaint("Synthetic");
        invalid.setTriagedBy("nurse-synthetic");
        assertThrows(IllegalArgumentException.class, () -> clinical.recordTriage(invalid));

        TriageAssessmentEntity normalTriage = new TriageAssessmentEntity();
        normalTriage.setEncounterId(encounter.getId());
        normalTriage.setEsiLevel(2);
        normalTriage.setChiefComplaint("Synthetic urgent symptoms");
        normalTriage.setTriagedBy("nurse-synthetic");

        FacilityContext.setFacilityId(foreign);
        assertThrows(SecurityException.class, () -> clinical.recordTriage(normalTriage));
        assertThrows(SecurityException.class,
                () -> clinical.startEncounter(facility, patient, "AMB", "OPD"));
        FacilityContext.setFacilityId(facility);
        clinical.recordTriage(normalTriage);

        VitalSignsEntity incomplete = new VitalSignsEntity();
        incomplete.setEncounterId(encounter.getId());
        incomplete.setPatientId(patient);
        incomplete.setHeartRateBpm(112);
        Map<String, Object> result = clinical.recordVitalsAndCheckPanic(incomplete);
        assertEquals(Boolean.FALSE, result.get("news2Complete"));
        assertNull(incomplete.getNews2Score());
        @SuppressWarnings("unchecked")
        List<CdsHooksEngine.CdsCard> alerts = (List<CdsHooksEngine.CdsCard>) result.get("cdssCards");
        assertTrue(alerts.stream().anyMatch(a -> "NEWS2_INCOMPLETE_VITALS".equals(a.uuid())));
        assertNull(readNews2Score(incomplete.getId()));

        ClinicalNoteRevisionEntity deniedNote = new ClinicalNoteRevisionEntity();
        deniedNote.setEncounterId(encounter.getId());
        deniedNote.setPatientId(patient);
        deniedNote.setNoteType("SOAP");
        deniedNote.setAuthorId("dr-expired");
        assertThrows(SecurityException.class, () -> clinical.signOrAmendNote(deniedNote, true));

        FacilityContext.setFacilityId(foreign);
        assertThrows(SecurityException.class, () -> clinical.signOrAmendNote(deniedNote, true));
    }

    private long countPatientEncounters(UUID facility, UUID patient) throws Exception {
        try (Connection connection = newConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM hc_encounters WHERE facility_id=? AND patient_id=?")) {
            statement.setObject(1, facility);
            statement.setObject(2, patient);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private long countForEncounter(String table, UUID encounter) throws Exception {
        // Only internal known constant table names appear at call sites.
        try (Connection connection = newConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE encounter_id=?")) {
            statement.setObject(1, encounter);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private String readNoteStatus(UUID facility, UUID patient, UUID encounter) throws Exception {
        try (Connection connection = newConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT n.status FROM hc_clinical_notes n JOIN hc_encounters e ON n.encounter_id=e.id "
                     + "WHERE e.facility_id=? AND n.patient_id=? AND n.encounter_id=?")) {
            statement.setObject(1, facility);
            statement.setObject(2, patient);
            statement.setObject(3, encounter);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private Integer readNews2Score(UUID vitalId) throws Exception {
        try (Connection connection = newConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT news2_score FROM hc_vital_signs WHERE id=?")) {
            statement.setObject(1, vitalId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                int score = result.getInt(1);
                return result.wasNull() ? null : score;
            }
        }
    }

    private Connection newConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
