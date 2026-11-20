package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.PractitionerCredentialEntity;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.WorkforceRosterEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.CredentialRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.WorkforceRosterRepository;
import com.opeshealth.healthcore.starters.WorkforceRosterStarter.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkforceRosterStarterTest {

    @Test
    void blocksExpiredLicenseAndAssignsShiftForValidLicensedPractitioner() {
        UUID facId = UUID.randomUUID();
        CredentialRepository credRepo = mock(CredentialRepository.class);
        WorkforceRosterRepository rosterRepo = mock(WorkforceRosterRepository.class);
        HospitalOpsService opsService = mock(HospitalOpsService.class);
        when(rosterRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(opsService.assignRosterShift(any())).thenAnswer(i -> i.getArgument(0));

        PractitionerCredentialEntity valid = new PractitionerCredentialEntity();
        valid.setFacilityId(facId);
        valid.setPractitionerId("dr.valid");
        valid.setLicenseNumber("CM-MED-9001");
        valid.setExpiryDate(LocalDate.now().plusYears(1));
        valid.setStatus("ACTIVE");
        when(credRepo.findAll()).thenReturn(List.of(valid));
        when(rosterRepo.findAll()).thenReturn(List.of());

        WorkforceCredentialAndRosterService service = new WorkforceCredentialAndRosterService(opsService, credRepo, rosterRepo);
        assertThrows(IllegalStateException.class, () -> service.assignVerifiedClinicalShift(
                new VerifiedShiftAssignmentRequest(facId, "dr.unlicensed", "ICU", LocalDate.now(), "DAY", false)));

        WorkforceRosterEntity shift = service.assignVerifiedClinicalShift(
                new VerifiedShiftAssignmentRequest(facId, "dr.valid", "ICU", LocalDate.now(), "DAY", true));
        assertEquals("dr.valid", shift.getPractitionerId());
    }
}
