/*
 * =============================================================================
 * Opes HealthCore SDK — Composable Workflow Engine Service & REST Gateway
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.workflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.workflow.catalog.ReusableWorkflowCatalog;
import com.opeshealth.healthcore.workflow.catalog.ReusableWorkflowCatalog.WorkflowTemplate;
import com.opeshealth.healthcore.workflow.domain.WorkflowEntities.*;
import com.opeshealth.healthcore.workflow.spi.WorkflowSpi.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Core orchestration engine executing facility-customizable clinical, EHR, HIE,
 * Pharmacy, Lab, and Public Health state machines.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Every transition is validated against either a facility's customized database definition
 *       or the fallback {@link ReusableWorkflowCatalog} blueprint.</li>
 *   <li>All matching {@link WorkflowStepHookSpi} beans run deterministically inside the ACID transaction.</li>
 *   <li>Every commit produces an immutable {@link WorkflowStepLogEntity} and an outbox domain event
 *       ({@code WorkflowTransitionedEvent} / {@code WorkflowSlaBreachedEvent}) for Kafka/FHIR listeners.</li>
 * </ul></p>
 */
public class WorkflowOrchestrationService {

    public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinitionEntity, UUID> {
        Optional<WorkflowDefinitionEntity> findTopByFacilityIdAndWorkflowCodeAndActiveTrueOrderByVersionNumberDesc(
                UUID facilityId, String workflowCode);
        List<WorkflowDefinitionEntity> findByFacilityIdAndActiveTrueOrderByWorkflowCodeAsc(UUID facilityId);
    }

    public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstanceEntity, UUID> {
        List<WorkflowInstanceEntity> findByFacilityIdAndStatusOrderBySlaDeadlineAtAsc(UUID facilityId, String status);
        List<WorkflowInstanceEntity> findByFacilityIdAndSubjectPatientIdOrderByStartedAtDesc(UUID facilityId, UUID patientId);
        List<WorkflowInstanceEntity> findByStatusAndCompletedAtIsNullAndSlaDeadlineAtBefore(String status, Instant cutoff);
    }

    public interface WorkflowStepLogRepository extends JpaRepository<WorkflowStepLogEntity, UUID> {
        List<WorkflowStepLogEntity> findByInstanceIdOrderByTransitionedAtAsc(UUID instanceId);
    }

    private final WorkflowDefinitionRepository definitionRepo;
    private final WorkflowInstanceRepository instanceRepo;
    private final WorkflowStepLogRepository stepLogRepo;
    private final OutboxRelayService.OutboxRepository outboxRepo;
    private final List<WorkflowStepHookSpi> stepHooks;
    private final List<WorkflowSlaEscalationSpi> slaHooks;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WorkflowOrchestrationService(
            WorkflowDefinitionRepository definitionRepo,
            WorkflowInstanceRepository instanceRepo,
            WorkflowStepLogRepository stepLogRepo,
            OutboxRelayService.OutboxRepository outboxRepo,
            List<WorkflowStepHookSpi> stepHooks,
            List<WorkflowSlaEscalationSpi> slaHooks) {
        this.definitionRepo = definitionRepo;
        this.instanceRepo = instanceRepo;
        this.stepLogRepo = stepLogRepo;
        this.outboxRepo = outboxRepo;
        this.stepHooks = stepHooks.stream()
                .sorted(Comparator.comparingInt(WorkflowStepHookSpi::order))
                .toList();
        this.slaHooks = slaHooks;
    }

    /**
     * Seeds all 8 standard HealthCore clinical/EHR/HIE workflow definitions idempotently
     * for a newly onboarded hospital, clinic, lab, pharmacy, or ministry tenant.
     */
    @Transactional
    @ClinicalAudit(action = "WORKFLOW_SEED_CATALOG", resourceType = "WorkflowDefinition")
    public List<WorkflowDefinitionEntity> seedCatalogForFacility(UUID facilityId) {
        List<WorkflowDefinitionEntity> created = new ArrayList<>();
        for (WorkflowTemplate t : ReusableWorkflowCatalog.allTemplates().values()) {
            Optional<WorkflowDefinitionEntity> existing = definitionRepo
                    .findTopByFacilityIdAndWorkflowCodeAndActiveTrueOrderByVersionNumberDesc(facilityId, t.workflowCode());
            if (existing.isEmpty()) {
                WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
                def.setFacilityId(facilityId);
                def.setWorkflowCode(t.workflowCode());
                def.setDomainCategory(t.domainCategory().name());
                def.setVersionNumber(1);
                def.setInitialStage(t.initialStage());
                def.setAllowedTransitionsJson(toJson(t.allowedTransitions()));
                def.setTerminalStagesCsv(String.join(",", t.terminalStages()));
                def.setSlaMinutes(t.defaultSlaMinutes());
                def.setActive(true);
                created.add(definitionRepo.save(def));
            }
        }
        return created;
    }

    /**
     * Registers or publishes a new version of a custom facility state machine without redeploying code.
     */
    @Transactional
    @ClinicalAudit(action = "WORKFLOW_DEFINE_CUSTOM", resourceType = "WorkflowDefinition")
    public WorkflowDefinitionEntity registerCustomDefinition(
            UUID facilityId,
            String workflowCode,
            WorkflowDomainCategory category,
            String initialStage,
            Map<String, Set<String>> allowedTransitions,
            Set<String> terminalStages,
            int slaMinutes) {
        if (slaMinutes <= 0) {
            throw new IllegalArgumentException("Workflow SLA minutes must be positive");
        }
        String normalizedCode = workflowCode.trim().toUpperCase(Locale.ROOT);
        int nextVersion = definitionRepo
                .findTopByFacilityIdAndWorkflowCodeAndActiveTrueOrderByVersionNumberDesc(facilityId, normalizedCode)
                .map(prev -> {
                    prev.setActive(false);
                    definitionRepo.save(prev);
                    return prev.getVersionNumber() + 1;
                })
                .orElse(1);

        WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
        def.setFacilityId(facilityId);
        def.setWorkflowCode(normalizedCode);
        def.setDomainCategory(category.name());
        def.setVersionNumber(nextVersion);
        def.setInitialStage(initialStage.trim().toUpperCase(Locale.ROOT));
        def.setAllowedTransitionsJson(toJson(allowedTransitions));
        def.setTerminalStagesCsv(String.join(",", terminalStages));
        def.setSlaMinutes(slaMinutes);
        def.setActive(true);
        return definitionRepo.save(def);
    }

    /**
     * Launches a live workflow instance with SLA calculation adjusted by priority tier
     * (STAT = 25% of baseline SLA, min 15 min; URGENT = 50% of baseline; ROUTINE = 100%).
     */
    @Transactional
    @ClinicalAudit(action = "WORKFLOW_START", resourceType = "WorkflowInstance")
    public WorkflowInstanceEntity startWorkflow(
            UUID facilityId,
            String workflowCode,
            UUID subjectPatientId,
            String externalCorrelationId,
            WorkflowPriority priority,
            Map<String, Object> initialContext,
            String actorId,
            String actorRole) {
        ResolvedGraph graph = resolveGraph(facilityId, workflowCode);
        WorkflowPriority effPriority = priority != null ? priority : WorkflowPriority.ROUTINE;
        int effectiveSlaMinutes = switch (effPriority) {
            case STAT -> Math.max(15, graph.slaMinutes() / 4);
            case URGENT -> Math.max(30, graph.slaMinutes() / 2);
            case ROUTINE -> graph.slaMinutes();
        };

        Instant now = Instant.now();
        WorkflowInstanceEntity inst = new WorkflowInstanceEntity();
        inst.setFacilityId(facilityId);
        inst.setWorkflowCode(graph.workflowCode());
        inst.setDefinitionVersion(graph.versionNumber());
        inst.setSubjectPatientId(subjectPatientId);
        inst.setExternalCorrelationId(externalCorrelationId);
        inst.setCurrentStage(graph.initialStage());
        inst.setPriority(effPriority.name());
        inst.setStatus("ACTIVE");
        inst.setContextJson(initialContext != null && !initialContext.isEmpty() ? toJson(initialContext) : "{}");
        inst.setStartedAt(now);
        inst.setSlaDeadlineAt(now.plus(Duration.ofMinutes(effectiveSlaMinutes)));

        WorkflowInstanceEntity saved = instanceRepo.save(inst);
        recordStepLog(saved.getFacilityId(), saved.getId(), null, saved.getCurrentStage(),
                actorId, actorRole, "Workflow started", initialContext);
        emitOutboxEvent(saved, "WorkflowStartedEvent");
        return saved;
    }

    /**
     * Validates and commits a stage transition inside an ACID transaction, executing all
     * matching {@link WorkflowStepHookSpi} pre/post gates and recording audit/outbox trails.
     */
    @Transactional
    @ClinicalAudit(action = "WORKFLOW_TRANSITION", resourceType = "WorkflowInstance")
    public WorkflowInstanceEntity transitionWorkflow(
            UUID instanceId,
            String targetStage,
            String actorId,
            String actorRole,
            String notes,
            Map<String, Object> metadata) {
        WorkflowInstanceEntity inst = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new NoSuchElementException("Workflow instance not found: " + instanceId));

        if ("COMPLETED".equals(inst.getStatus()) || "CANCELLED".equals(inst.getStatus())) {
            throw new IllegalStateException("Workflow instance is already finalized with status " + inst.getStatus());
        }

        ResolvedGraph graph = resolveGraph(inst.getFacilityId(), inst.getWorkflowCode());
        String fromStage = inst.getCurrentStage();
        String normalizedTarget = targetStage.trim().toUpperCase(Locale.ROOT);
        Set<String> allowedTargets = graph.allowedTransitions().getOrDefault(fromStage, Set.of());
        if (!allowedTargets.contains(normalizedTarget)) {
            throw new IllegalStateException("Invalid workflow transition for " + inst.getWorkflowCode()
                    + ": " + fromStage + " -> " + normalizedTarget + " (allowed: " + allowedTargets + ")");
        }

        WorkflowTransitionContext ctx = new WorkflowTransitionContext(
                inst.getFacilityId(), inst.getId(), inst.getWorkflowCode(),
                inst.getSubjectPatientId(), fromStage, normalizedTarget,
                actorId, actorRole, notes, metadata != null ? metadata : Map.of()
        );

        // Execute pre-commit clinical/security/regulatory gates in deterministic order.
        List<WorkflowStepHookSpi> matchingHooks = stepHooks.stream()
                .filter(h -> h.supportsWorkflow(inst.getWorkflowCode()))
                .toList();
        for (WorkflowStepHookSpi hook : matchingHooks) {
            hook.beforeTransition(ctx);
        }

        inst.setCurrentStage(normalizedTarget);
        if (metadata != null && !metadata.isEmpty()) {
            Map<String, Object> merged = parseContextMap(inst.getContextJson());
            merged.putAll(metadata);
            inst.setContextJson(toJson(merged));
        }

        if (graph.terminalStages().contains(normalizedTarget)) {
            boolean cancelled = normalizedTarget.contains("CANCEL") || normalizedTarget.contains("DENIED")
                    || normalizedTarget.contains("REJECTED") || normalizedTarget.contains("POSTPONED");
            inst.setStatus(cancelled ? "CANCELLED" : "COMPLETED");
            inst.setCompletedAt(Instant.now());
        }

        WorkflowInstanceEntity updated = instanceRepo.save(inst);
        recordStepLog(updated.getFacilityId(), updated.getId(), fromStage, normalizedTarget,
                actorId, actorRole, notes, metadata);
        emitOutboxEvent(updated, "WorkflowTransitionedEvent");

        for (WorkflowStepHookSpi hook : matchingHooks) {
            hook.afterTransition(ctx);
        }
        return updated;
    }

    /**
     * Background SLA watch scanning overdue ACTIVE workflows (benefiting from PostgreSQL's
     * partial index {@code idx_hc_wf_active_sla}) and escalating them cleanly via outbox + SPI hooks.
     */
    @Scheduled(fixedDelayString = "${healthcore.workflow.sla-scan-interval-ms:60000}")
    @Transactional
    public int escalateOverdueSlaInstances() {
        List<WorkflowInstanceEntity> overdue = instanceRepo
                .findByStatusAndCompletedAtIsNullAndSlaDeadlineAtBefore("ACTIVE", Instant.now());
        for (WorkflowInstanceEntity inst : overdue) {
            inst.setStatus("ESCALATED");
            instanceRepo.save(inst);
            emitOutboxEvent(inst, "WorkflowSlaBreachedEvent");
            for (WorkflowSlaEscalationSpi slaHook : slaHooks) {
                slaHook.onSlaBreached(inst);
            }
        }
        return overdue.size();
    }

    public List<WorkflowStepLogEntity> getAuditTimeline(UUID instanceId) {
        return stepLogRepo.findByInstanceIdOrderByTransitionedAtAsc(instanceId);
    }

    public List<WorkflowInstanceEntity> listActiveByFacility(UUID facilityId) {
        return instanceRepo.findByFacilityIdAndStatusOrderBySlaDeadlineAtAsc(facilityId, "ACTIVE");
    }

    public List<WorkflowDefinitionEntity> listDefinitions(UUID facilityId) {
        return definitionRepo.findByFacilityIdAndActiveTrueOrderByWorkflowCodeAsc(facilityId);
    }

    private ResolvedGraph resolveGraph(UUID facilityId, String workflowCode) {
        String code = workflowCode.trim().toUpperCase(Locale.ROOT);
        Optional<WorkflowDefinitionEntity> custom = definitionRepo
                .findTopByFacilityIdAndWorkflowCodeAndActiveTrueOrderByVersionNumberDesc(facilityId, code);
        if (custom.isPresent()) {
            WorkflowDefinitionEntity d = custom.get();
            Map<String, Set<String>> map = parseTransitionsMap(d.getAllowedTransitionsJson());
            Set<String> terminals = new HashSet<>();
            for (String s : d.getTerminalStagesCsv().split(",")) {
                if (!s.isBlank()) {
                    terminals.add(s.trim().toUpperCase(Locale.ROOT));
                }
            }
            return new ResolvedGraph(d.getWorkflowCode(), d.getVersionNumber(), d.getInitialStage(),
                    terminals, d.getSlaMinutes(), map);
        }
        WorkflowTemplate fallback = ReusableWorkflowCatalog.requireTemplate(code);
        return new ResolvedGraph(fallback.workflowCode(), 1, fallback.initialStage(),
                fallback.terminalStages(), fallback.defaultSlaMinutes(), fallback.allowedTransitions());
    }

    private void recordStepLog(
            UUID facilityId, UUID instanceId, String fromStage, String toStage,
            String actorId, String actorRole, String notes, Map<String, Object> metadata) {
        WorkflowStepLogEntity log = new WorkflowStepLogEntity();
        log.setFacilityId(facilityId);
        log.setInstanceId(instanceId);
        log.setFromStage(fromStage);
        log.setToStage(toStage);
        log.setActorId(actorId != null && !actorId.isBlank() ? actorId : "SYSTEM");
        log.setActorRole(actorRole != null && !actorRole.isBlank() ? actorRole : "CLINICAL_SYSTEM");
        log.setNotes(notes);
        log.setTransitionMetadataJson(metadata != null && !metadata.isEmpty() ? toJson(metadata) : "{}");
        stepLogRepo.save(log);
    }

    private void emitOutboxEvent(WorkflowInstanceEntity inst, String eventType) {
        TransactionalOutboxEntity evt = new TransactionalOutboxEntity();
        evt.setIdempotencyKey("WF-" + inst.getId() + "-" + eventType + "-" + inst.getCurrentStage() + "-" + UuidV7Generator.nextUuid());
        evt.setFacilityId(inst.getFacilityId());
        evt.setAggregateType("WorkflowInstance");
        evt.setAggregateId(inst.getId());
        evt.setEventType(eventType);
        evt.setPayloadJson(toJson(Map.of(
                "instanceId", inst.getId(),
                "workflowCode", inst.getWorkflowCode(),
                "currentStage", inst.getCurrentStage(),
                "status", inst.getStatus(),
                "priority", inst.getPriority(),
                "subjectPatientId", inst.getSubjectPatientId() != null ? inst.getSubjectPatientId().toString() : ""
        )));
        outboxRepo.save(evt);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private Map<String, Object> parseContextMap(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new LinkedHashMap<>();
            }
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Set<String>> parseTransitionsMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("Corrupt workflow definition transitions JSON", ex);
        }
    }

    private record ResolvedGraph(
            String workflowCode,
            int versionNumber,
            String initialStage,
            Set<String> terminalStages,
            int slaMinutes,
            Map<String, Set<String>> allowedTransitions
    ) {}

    public record StartWorkflowRequest(
            String workflowCode,
            UUID subjectPatientId,
            String externalCorrelationId,
            WorkflowPriority priority,
            Map<String, Object> initialContext,
            String actorId,
            String actorRole
    ) {}

    public record TransitionWorkflowRequest(
            String targetStage,
            String actorId,
            String actorRole,
            String notes,
            Map<String, Object> metadata
    ) {}

    public record CustomWorkflowDefinitionRequest(
            String workflowCode,
            WorkflowDomainCategory category,
            String initialStage,
            Map<String, Set<String>> allowedTransitions,
            Set<String> terminalStages,
            int slaMinutes
    ) {}

    /**
     * REST Gateway exposing reusable EMR/EHR/HIE workflow blueprints, runtime instance
     * lifecycle management, SLA escalation monitoring, and transition audit timelines.
     */
    @RestController
    @RequestMapping("/api/v1/workflows")
    @Tag(name = "Composable Clinical & HIE Workflows", description = "Facility-customizable state machines, SLA escalations, and pluggable WorkflowStepHookSpi gates")
    public static class WorkflowGatewayController {
        private final WorkflowOrchestrationService service;

        public WorkflowGatewayController(WorkflowOrchestrationService service) {
            this.service = service;
        }

        @GetMapping("/catalog")
        @Operation(summary = "List the 8 built-in reusable EMR/EHR/HIE/PIS/LIS/OT/CHW workflow templates")
        @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_chw:visit:write') or hasAuthority('PERM_facility:manage')")
        public Collection<WorkflowTemplate> catalog() {
            return ReusableWorkflowCatalog.allTemplates().values();
        }

        @PostMapping("/catalog/seed")
        @Operation(summary = "Seed all 8 standard reusable workflow definitions for the active facility")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin')")
        public List<WorkflowDefinitionEntity> seedCatalog() {
            return service.seedCatalogForFacility(FacilityContext.requireFacilityId());
        }

        @GetMapping("/definitions")
        @Operation(summary = "List active custom & seeded workflow definitions for the active facility")
        @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_chw:visit:write') or hasAuthority('PERM_facility:manage')")
        public List<WorkflowDefinitionEntity> listDefinitions() {
            return service.listDefinitions(FacilityContext.requireFacilityId());
        }

        @PostMapping("/definitions")
        @Operation(summary = "Publish or version a custom facility-specific workflow state machine")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_template:publish') or hasAuthority('PERM_system:admin')")
        public WorkflowDefinitionEntity defineCustom(@RequestBody CustomWorkflowDefinitionRequest req) {
            return service.registerCustomDefinition(
                    FacilityContext.requireFacilityId(),
                    req.workflowCode(), req.category(), req.initialStage(),
                    req.allowedTransitions(), req.terminalStages(), req.slaMinutes());
        }

        @PostMapping("/instances")
        @Operation(summary = "Start a tracked clinical, lab, pharmacy, HIE, or CHW workflow instance")
        @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_chw:visit:write') or hasAuthority('PERM_facility:manage')")
        public WorkflowInstanceEntity start(@RequestBody StartWorkflowRequest req) {
            return service.startWorkflow(
                    FacilityContext.requireFacilityId(),
                    req.workflowCode(), req.subjectPatientId(), req.externalCorrelationId(),
                    req.priority(), req.initialContext(), req.actorId(), req.actorRole());
        }

        @PostMapping("/instances/{instanceId}/transition")
        @Operation(summary = "Transition a live workflow instance after executing WorkflowStepHookSpi gates")
        @PreAuthorize("hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_lab:verify') or hasAuthority('PERM_rx:dispense') or hasAuthority('PERM_chw:visit:write') or hasAuthority('PERM_facility:manage')")
        public WorkflowInstanceEntity transition(
                @PathVariable UUID instanceId,
                @RequestBody TransitionWorkflowRequest req) {
            return service.transitionWorkflow(
                    instanceId, req.targetStage(), req.actorId(), req.actorRole(), req.notes(), req.metadata());
        }

        @GetMapping("/instances/active")
        @Operation(summary = "List active facility workflow instances ordered by nearest SLA deadline")
        @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_chw:visit:write') or hasAuthority('PERM_facility:manage')")
        public List<WorkflowInstanceEntity> listActive() {
            return service.listActiveByFacility(FacilityContext.requireFacilityId());
        }

        @GetMapping("/instances/{instanceId}/timeline")
        @Operation(summary = "Retrieve immutable step-by-step audit timeline for a workflow instance")
        @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_audit:read')")
        public List<WorkflowStepLogEntity> timeline(@PathVariable UUID instanceId) {
            return service.getAuditTimeline(instanceId);
        }
    }
}
