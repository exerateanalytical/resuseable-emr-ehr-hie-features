package com.opeshealth.healthcore.templates;

import com.opeshealth.healthcore.templates.domain.TemplatesAndOnboardingEntities.*;
import com.opeshealth.healthcore.templates.service.TemplatesAndOperationsService;
import com.opeshealth.healthcore.templates.service.TemplatesAndOperationsService.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TemplatesAndOperationsServiceTest {

    private FacilityNodeRepository facilityRepo;
    private DocumentTemplateRepository templateRepo;
    private QuestionnaireResponseRepository responseRepo;
    private BackupManifestRepository backupRepo;
    private EdgeDrStateRepository edgeDrRepo;
    private TemplatesAndOperationsService service;

    @BeforeEach
    void setUp() {
        facilityRepo = mock(FacilityNodeRepository.class);
        templateRepo = mock(DocumentTemplateRepository.class);
        responseRepo = mock(QuestionnaireResponseRepository.class);
        backupRepo = mock(BackupManifestRepository.class);
        edgeDrRepo = mock(EdgeDrStateRepository.class);
        when(facilityRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(responseRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(backupRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(edgeDrRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new TemplatesAndOperationsService(
                facilityRepo, templateRepo, responseRepo, backupRepo, edgeDrRepo,
                mock(JdbcTemplate.class), List.of());
    }

    @Test
    void buildsRoleDashboardAndVerifiesBackupRestoreAndFailoverState() {
        Map<String, Object> dash = service.buildRoleDashboard("CLINICIAN");
        assertNotNull(dash);
        assertFalse(dash.isEmpty());

        BackupManifestEntity manifest = service.recordBackupSnapshot("postgresql", "s3://backups/healthcore-2026.dump");
        assertNotNull(manifest);
        when(backupRepo.findById(manifest.getId())).thenReturn(Optional.of(manifest));

        BackupManifestEntity verified = service.verifyRestoreDrill(manifest.getId());
        assertTrue(verified.isRestoreVerified());

        UUID facId = UUID.randomUUID();
        EdgeDrStateEntity state = service.setFailoverMode(facId, "EDGE_ISOLATED", "WAN fiber cut", "ops.lead");
        assertEquals("EDGE_ISOLATED", state.getFailoverMode());
    }
}
