/*
 * =============================================================================
 * Opes HealthCore SDK — DICOMweb PACS Proxy Conformance Tests (DICOM-001)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.hie.service;

import com.opeshealth.healthcore.hie.domain.HieEntities.DicomStudyEntity;
import com.opeshealth.healthcore.hie.service.HieInteropService.DicomStudyRepository;
import com.opeshealth.healthcore.hie.service.HieInteropService.HieDlqRepository;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DicomPacsProxyServiceTest {

    private DicomStudyRepository dicomRepo;
    private HieDlqRepository dlqRepo;
    private DicomPacsProxyService service;
    private UUID facilityA;
    private UUID facilityB;

    @BeforeEach
    void setUp() {
        dicomRepo = mock(DicomStudyRepository.class);
        dlqRepo = mock(HieDlqRepository.class);
        service = new DicomPacsProxyService(dicomRepo, dlqRepo);
        facilityA = UUID.randomUUID();
        facilityB = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityA);
    }

    @AfterEach
    void tearDown() {
        FacilityContext.clear();
    }

    @Test
    void rejectsInvalidDicomUids() {
        assertThrows(IllegalArgumentException.class, () ->
                DicomPacsProxyService.validateDicomUid("not-a-valid-uid", "StudyInstanceUID"));
        assertThrows(IllegalArgumentException.class, () ->
                DicomPacsProxyService.validateDicomUid("1.2.0840.10008", "StudyInstanceUID"));
        assertThrows(IllegalArgumentException.class, () ->
                DicomPacsProxyService.validateDicomUid("1.2." + "9".repeat(70), "StudyInstanceUID"));
        assertDoesNotThrow(() ->
                DicomPacsProxyService.validateDicomUid("1.2.840.10008.5.1.4.1.1.2.0", "StudyInstanceUID"));
    }

    @Test
    void rejectsEmptyBinaryPayloadOnStowRs() {
        assertThrows(IllegalArgumentException.class, () ->
                service.storeBinaryStudy(facilityA, UUID.randomUUID(), "1.2.840.10008.1.2", "CT", "Head CT", "application/dicom", new byte[0]));
        verifyNoInteractions(dicomRepo);
    }

    @Test
    void deniesCrossFacilityStowRs() {
        assertThrows(AccessDeniedException.class, () ->
                service.storeBinaryStudy(facilityB, UUID.randomUUID(), "1.2.840.10008.1.2", "CT", "Head CT", "application/dicom", new byte[]{1, 2, 3}));
        verifyNoInteractions(dicomRepo);
    }

    @Test
    void throwsUnsupportedWhenPacsDisabledInsteadOfFakingSuccess() {
        assertThrows(UnsupportedOperationException.class, () ->
                service.storeBinaryStudy(facilityA, UUID.randomUUID(), "1.2.840.10008.1.2", "CT", "Head CT", "application/dicom", new byte[]{1, 2, 3}));
        verifyNoInteractions(dicomRepo);
    }

    @Test
    void wadoRejectsInvalidUidAndReturnsNotImplementedWhenPacsDisabled() {
        assertThrows(IllegalArgumentException.class, () ->
                service.retrieveWadoBinary("bad/uid/../etc", null, null));

        DicomStudyEntity study = new DicomStudyEntity();
        study.setFacilityId(facilityA);
        study.setStudyInstanceUid("1.2.840.10008.5.1.4");
        when(dicomRepo.findFirstByFacilityIdAndStudyInstanceUid(facilityA, "1.2.840.10008.5.1.4"))
                .thenReturn(Optional.of(study));

        ResponseEntity<byte[]> response = service.retrieveWadoBinary("1.2.840.10008.5.1.4", null, null);
        assertEquals(HttpStatus.NOT_IMPLEMENTED, response.getStatusCode());
    }

    @Test
    void qidoQueriesOnlyAuthenticatedTenantRegistry() {
        DicomStudyEntity study = new DicomStudyEntity();
        study.setFacilityId(facilityA);
        study.setModality("MR");
        study.setStudyInstanceUid("1.2.840.10008.9.1");
        when(dicomRepo.findByFacilityIdOrderByStudyDateDesc(facilityA)).thenReturn(List.of(study));

        Object result = service.queryQidoStudies(null, "MR");
        assertInstanceOf(List.class, result);
        assertEquals(1, ((List<?>) result).size());
        verify(dicomRepo).findByFacilityIdOrderByStudyDateDesc(facilityA);
        verify(dicomRepo, never()).findAll();
    }
}
