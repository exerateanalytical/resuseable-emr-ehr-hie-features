package com.healthcore.api;

import com.healthcore.facility.FacilityEntity;
import com.healthcore.facility.FacilityRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/facilities")
public class FacilityController {

    private final FacilityRepository facilityRepository;

    public FacilityController(FacilityRepository facilityRepository) {
        this.facilityRepository = facilityRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public FacilityEntity createFacility(@Valid @RequestBody CreateFacilityRequest req) {
        FacilityEntity f = new FacilityEntity();
        f.setCode(req.code().trim().toUpperCase());
        f.setName(req.name().trim());
        f.setFacilityType(req.facilityType().trim().toUpperCase());
        f.setRegion(req.region());
        f.setCity(req.city());
        return facilityRepository.save(f);
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<FacilityEntity> listFacilities() {
        return facilityRepository.findAll();
    }

    public record CreateFacilityRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String facilityType,
            String region,
            String city
    ) {}
}
