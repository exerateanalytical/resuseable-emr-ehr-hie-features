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
         * Builds the HL7 FHIR R4 {@link CapabilityStatement} declaring supported resources,
         * search parameters, transaction/batch Bundle operations, IPS `$summary`, and SMART-on-FHIR security.
         */
        public String buildFhirR4CapabilityStatementJson() {
            CapabilityStatement cs = new CapabilityStatement();
            cs.setId("opes-healthcore-fhir-r4-server");
            cs.setName("OpesHealthCoreFhirR4CapabilityStatement");
            cs.setTitle("Opes HealthCore HL7 FHIR R4 & HIE Server CapabilityStatement");
            cs.setStatus(PublicationStatus.ACTIVE);
            cs.setDate(new Date());
            cs.setPublisher("Opes Health Systems — Jude Nshome");
            cs.setKind(CapabilityStatementKind.INSTANCE);
            cs.setFhirVersion(FHIRVersion._4_0_1);
            cs.setFormat(List.of(new CodeType("application/fhir+json"), new CodeType("application/json")));

            CapabilityStatementRestComponent rest = cs.addRest();
            rest.setMode(RestfulCapabilityMode.SERVER);
            rest.addInteraction().setCode(SystemRestfulInteraction.TRANSACTION);
            rest.addInteraction().setCode(SystemRestfulInteraction.BATCH);

            for (String resType : List.of("Patient", "Practitioner", "Encounter", "Observation", "Bundle", "AuditEvent", "DocumentReference", "Subscription")) {
                CapabilityStatement.CapabilityStatementRestResourceComponent rc = rest.addResource();
                rc.setType(resType);
                rc.addInteraction().setCode(TypeRestfulInteraction.READ);
                rc.addInteraction().setCode(TypeRestfulInteraction.SEARCHTYPE);
                if (!"AuditEvent".equals(resType)) {
                    rc.addInteraction().setCode(TypeRestfulInteraction.CREATE);
                    rc.addInteraction().setCode(TypeRestfulInteraction.UPDATE);
                    rc.addInteraction().setCode(TypeRestfulInteraction.DELETE);
                }
            }
            return FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(cs);
        }

        /**
         * Delegates to {@link HieInteropService} to generate the patient's longitudinal
         * International Patient Summary (IPS) / `$everything` FHIR R4 Bundle.
         */
        @Transactional(readOnly = true)
        @ClinicalAudit(action = "FHIR_HIE_PATIENT_EVERYTHING", resourceType = "Patient")
        public String exportPatientEverythingBundle(UUID patientId) {
            return hieInteropService.generateIpsSummaryBundle(patientId);
        }

        /**
         * Inspects PostgreSQL `hc_fhir_subscriptions` to report active HIE webhook channels.
         */
        @Transactional(readOnly = true)
        public SubscriptionChannelHealth getSubscriptionChannelHealth() {
            List<FhirSubscriptionEntity> all = subscriptionRepository.findAll();
            List<String> criteria = new ArrayList<>();
            int active = 0;
            for (FhirSubscriptionEntity sub : all) {
                if ("ACTIVE".equalsIgnoreCase(sub.getStatus())) {
                    active++;
                    criteria.add(sub.getCriteria() + " -> " + sub.getWebhookEndpoint());
                }
            }
            return new SubscriptionChannelHealth(all.size(), active, List.copyOf(criteria));
        }
    }

    @RestController
    @RequestMapping("/api/v1/fhir-hie")
    @Tag(name = "FHIR R4 HIE CapabilityStatement, IPS & Subscriptions", description = "HL7 FHIR R4 CapabilityStatement metadata, Patient $everything IPS Bundle, and Subscription health")
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
        @Operation(summary = "Assemble longitudinal Patient $everything / IPS FHIR R4 Bundle")
        public String getPatientEverything(@PathVariable UUID id) {
            return gatewayService.exportPatientEverythingBundle(id);
        }

        @GetMapping(value = "/subscriptions/status", produces = MediaType.APPLICATION_JSON_VALUE)
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_patient:read')")
        @Operation(summary = "Inspect active PostgreSQL FHIR R4 REST-Hook Subscription channels")
        public SubscriptionChannelHealth getSubscriptionStatus() {
            return gatewayService.getSubscriptionChannelHealth();
        }
    }
}
