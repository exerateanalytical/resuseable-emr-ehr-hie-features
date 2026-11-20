package com.opeshealth.healthcore.hie.service;

import com.opeshealth.healthcore.hie.domain.HieEntities.DicomStudyEntity;
import com.opeshealth.healthcore.hie.domain.HieEntities.IheDocumentEntity;
import com.opeshealth.healthcore.hie.service.HieInteropService.*;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DicomWebAndIheStandardsFixtureTest {

    private MpiCommandService.PatientRepository patientRepo;
    private IheDocumentRepository docRepo;
    private DicomStudyRepository dicomRepo;
    private HieInteropService service;
    private UUID facilityId;

    @BeforeEach
    void setUp() {
        patientRepo = mock(MpiCommandService.PatientRepository.class);
        docRepo = mock(IheDocumentRepository.class);
        dicomRepo = mock(DicomStudyRepository.class);
        when(docRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(dicomRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new HieInteropService(
                patientRepo,
                mock(FhirSubscriptionRepository.class),
                dicomRepo,
                mock(HieDlqRepository.class),
                docRepo,
                mock(JdbcTemplate.class));
        facilityId = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityId);
    }

    @AfterEach
    void tearDown() {
        FacilityContext.clear();
    }

    @Test
    void generatesValidCdaR2XmlAndIhePixmAndAstmE1394Fixtures() {
        PatientAggregateEntity patient = new PatientAggregateEntity();
        patient.setFacilityId(facilityId);
        patient.setMrn("MRN-CDA-01");
        patient.setFamilyName("Nkomo");
        patient.setGivenName("Paul");
        patient.setBirthDate(LocalDate.of(1984, 5, 14));
        patient.setGender("male");
        when(patientRepo.findByIdAndFacilityId(patient.getId(), facilityId)).thenReturn(Optional.of(patient));

        String cdaXml = service.generateCdaR2Xml(patient.getId(), "Synthetic patient summary");
        assertTrue(cdaXml.contains("<ClinicalDocument"));
        assertTrue(cdaXml.contains("MRN-CDA-01"));

        Map<String, Object> pix = service.ihePixmLookup(patient.getId(), "urn:opes:mrn");
        assertNotNull(pix);

        List<Map<String, String>> astmRows = service.parseAstmAnalyzerFrame(
                "H|\\^&|||ANALYZER|||||||P|1\rR|1|^^^2345-7|5.4|mmol/L||N||F\rL|1|N");
        assertEquals(1, astmRows.size());
    }

    @Test
    void verifiesDicomStudyAndIheDocumentTenantBoundary() {
        DicomStudyEntity study = new DicomStudyEntity();
        study.setFacilityId(facilityId);
        study.setPatientId(UUID.randomUUID());
        study.setStudyInstanceUid("1.2.826.0.1.3680043.10.942.777");
        study.setModality("MR");
        assertDoesNotThrow(() -> HieInteropService.enforcePayloadFacilityMatchesTenant(study.getFacilityId(), facilityId));

        UUID foreignFacility = UUID.randomUUID();
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> HieInteropService.enforcePayloadFacilityMatchesTenant(foreignFacility, facilityId));

        IheDocumentEntity doc = new IheDocumentEntity();
        doc.setFacilityId(facilityId);
        assertDoesNotThrow(() -> HieInteropService.enforcePayloadFacilityMatchesTenant(doc.getFacilityId(), facilityId));
    }
}
