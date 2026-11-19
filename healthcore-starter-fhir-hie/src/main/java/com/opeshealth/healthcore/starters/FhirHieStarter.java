/*
 * =============================================================================
 * Opes HealthCore SDK — FHIR R4 HIE IPS, Bulk $export & Subscriptions Starter
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.starters;

import ca.uhn.fhir.context.FhirContext;
import com.opeshealth.healthcore.hie.autoconfigure.HieInteropAutoConfiguration;
import com.opeshealth.healthcore.hie.domain.HieEntities.FhirSubscriptionEntity;
import com.opeshealth.healthcore.hie.service.HieInteropService;
import com.opeshealth.healthcore.hie.service.HieInteropService.FhirSubscriptionRepository;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementKind;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.RestfulCapabilityMode;
import org.hl7.fhir.r4.model.CapabilityStatement.SystemRestfulInteraction;
import org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Enumerations.FHIRVersion;
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * End-to-end HL7 FHIR R4 Health Information Exchange (HIE) module generating the server's
 * normative {@link CapabilityStatement}, assembling International Patient Summary (IPS)
 * `$summary` &amp; `$everything` compartment Bundles, and monitoring active PostgreSQL
 * REST-Hook subscription channels.
 */
@AutoConfiguration(after = HieInteropAutoConfiguration.class)
public class FhirHieStarter {

    private static final FhirContext FHIR_R4 = FhirContext.forR4();

    public record SubscriptionChannelHealth(
            int totalRegisteredSubscriptions,
            int activeSubscriptions,
            List<String> subscribedCriteriaList
    ) {}

    @Bean
    @ConditionalOnMissingBean
    public FhirHieExchangeGatewayService fhirHieExchangeGatewayService(
            HieInteropService hieInteropService,
            FhirSubscriptionRepository subscriptionRepository) {
        return new FhirHieExchangeGatewayService(hieInteropService, subscriptionRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public FhirHieExchangeGatewayController fhirHieExchangeGatewayController(
            FhirHieExchangeGatewayService gatewayService) {
        return new FhirHieExchangeGatewayController(gatewayService);
    }

    public static class FhirHieExchangeGatewayService {

        private final HieInteropService hieInteropService;
        private final FhirSubscriptionRepository subscriptionRepository;

        public FhirHieExchangeGatewayService(
                HieInteropService hieInteropService,
                FhirSubscriptionRepository subscriptionRepository) {
            this.hieInteropService = Objects.requireNonNull(hieInteropService, "HieInteropService is required");
            this.subscriptionRepository = Objects.requireNonNull(subscriptionRepository, "FhirSubscriptionRepository is required");
        }

        /**
         * Builds the HL7 FHIR R4 {@link CapabilityStatement} strictly reconciled with the
         * actually implemented HIE endpoints (delegating to {@link HieInteropService}).
         */
        public String buildFhirR4CapabilityStatementJson() {
            return hieInteropService.generateCapabilityStatementJson();
        }

        /**
         * Delegates to {@link HieInteropService} to generate the tenant-scoped patient summary
         * FHIR R4 Bundle assembled from persisted HIE records.
         */
        @Transactional(readOnly = true)
        @ClinicalAudit(action = "FHIR_HIE_PATIENT_EVERYTHING", resourceType = "Patient")
        public String exportPatientEverythingBundle(UUID patientId) {
            return hieInteropService.generateIpsSummaryBundle(patientId);
        }

        /**
         * Inspects tenant-scoped {@code hc_fhir_subscriptions} to report active HIE webhook channels
         * strictly within the caller's authenticated facility tenant.
         */
        @Transactional(readOnly = true)
        public SubscriptionChannelHealth getSubscriptionChannelHealth() {
            UUID facilityId = HieInteropService.requireTenantFacilityId();
            List<FhirSubscriptionEntity> facilitySubs = subscriptionRepository.findByFacilityId(facilityId);
            List<String> criteria = new ArrayList<>();
            int active = 0;
            for (FhirSubscriptionEntity sub : facilitySubs) {
                if ("ACTIVE".equalsIgnoreCase(sub.getStatus())) {
                    active++;
                    criteria.add(sub.getCriteria() + " -> " + sub.getEndpointUrl());
                }
            }
            return new SubscriptionChannelHealth(facilitySubs.size(), active, List.copyOf(criteria));
        }
    }

    @RestController
    @RequestMapping("/api/v1/fhir-hie")
    @Tag(name = "FHIR R4 HIE CapabilityStatement, Patient Summary & Subscriptions", description = "HL7 FHIR R4 CapabilityStatement metadata, tenant-scoped Patient summary Bundle, and Subscription health")
    public static class FhirHieExchangeGatewayController {

        private final FhirHieExchangeGatewayService gatewayService;

        public FhirHieExchangeGatewayController(FhirHieExchangeGatewayService gatewayService) {
            this.gatewayService = gatewayService;
        }

        @GetMapping(value = "/metadata", produces = "application/fhir+json")
        @Operation(summary = "Retrieve HL7 FHIR R4 Server CapabilityStatement")
        public String getCapabilityStatement() {
            return gatewayService.buildFhirR4CapabilityStatementJson();
        }

        @GetMapping(value = "/Patient/{id}/$everything", produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "Assemble tenant-scoped Patient summary FHIR R4 Bundle from persisted HIE records")
        public String getPatientEverything(@PathVariable UUID id) {
            return gatewayService.exportPatientEverythingBundle(id);
        }

        @GetMapping(value = "/subscriptions/status", produces = MediaType.APPLICATION_JSON_VALUE)
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin')")
        @Operation(summary = "Inspect active tenant-scoped FHIR R4 REST-Hook Subscription channels")
        public SubscriptionChannelHealth getSubscriptionStatus() {
            return gatewayService.getSubscriptionChannelHealth();
        }
    }
}
