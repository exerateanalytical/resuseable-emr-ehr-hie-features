/*
 * =============================================================================
 * Opes HealthCore SDK — Composable Workflow Engine JPA Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.workflow.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA aggregate entities backing Opes HealthCore's facility-customizable workflow engine.
 *
 * <p>Every entity participates in Hibernate's {@code facilityFilter} (defined once on
 * {@code TransactionalOutboxEntity}) and assigns sequential RFC 9562 UUIDv7 primary keys
 * so high-throughput inserts stay B-tree append-friendly across PostgreSQL, MSSQL, and MySQL.</p>
 */
public final class WorkflowEntities {

    private WorkflowEntities() {}

    /**
     * Facility-scoped workflow graph blueprint storing directed allowed transitions,
     * initial stage, terminal stages, and SLA budget in minutes.
     */
    @Entity
    @Table(name = "hc_workflow_definitions")
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class WorkflowDefinitionEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        /** Canonical workflow identifier (e.g. "OPD_CONSULTATION_FLOW", "EHR_HIE_EXCHANGE_FLOW"). */
        @Column(name = "workflow_code", nullable = false, length = 80)
        private String workflowCode;

        /** Target domain slice: EMR, EHR, HIE, PIS, LIS, SPECIALTY, PUBLIC_HEALTH. */
        @Column(name = "domain_category", nullable = false, length = 32)
        private String domainCategory;

        @Column(name = "version_number", nullable = false)
        private int versionNumber = 1;

        @Column(name = "initial_stage", nullable = false, length = 64)
        private String initialStage;

        /** JSON object mapping stage name -> array of valid target stage names. */
        @Column(name = "allowed_transitions_json", nullable = false, length = org.hibernate.Length.LONG32)
        private String allowedTransitionsJson;

        /** Comma-separated terminal stage names (e.g. "COMPLETED,CANCELLED"). */
        @Column(name = "terminal_stages_csv", nullable = false, length = 255)
        private String terminalStagesCsv;

        /** Turnaround Service-Level Agreement budget in minutes before automatic escalation. */
        @Column(name = "sla_minutes", nullable = false)
        private int slaMinutes = 240;

        @Column(name = "active", nullable = false)
        private boolean active = true;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getWorkflowCode() { return workflowCode; }
        public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }
        public String getDomainCategory() { return domainCategory; }
        public void setDomainCategory(String domainCategory) { this.domainCategory = domainCategory; }
        public int getVersionNumber() { return versionNumber; }
        public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }
        public String getInitialStage() { return initialStage; }
        public void setInitialStage(String initialStage) { this.initialStage = initialStage; }
        public String getAllowedTransitionsJson() { return allowedTransitionsJson; }
        public void setAllowedTransitionsJson(String allowedTransitionsJson) { this.allowedTransitionsJson = allowedTransitionsJson; }
        public String getTerminalStagesCsv() { return terminalStagesCsv; }
        public void setTerminalStagesCsv(String terminalStagesCsv) { this.terminalStagesCsv = terminalStagesCsv; }
        public int getSlaMinutes() { return slaMinutes; }
        public void setSlaMinutes(int slaMinutes) { this.slaMinutes = slaMinutes; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public Instant getCreatedAt() { return createdAt; }
    }

    /**
     * Live workflow execution instance tracking a patient encounter, lab specimen,
     * pharmacy order, HIE exchange, or CHW outbreak case across clinical gates.
     */
    @Entity
    @Table(name = "hc_workflow_instances")
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class WorkflowInstanceEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "workflow_code", nullable = false, length = 80)
        private String workflowCode;

        @Column(name = "definition_version", nullable = false)
        private int definitionVersion = 1;

        /** Optional patient UUID (nullable for HIE organizational or cold-chain device flows). */
        @Column(name = "subject_patient_id")
        private UUID subjectPatientId;

        /** External FHIR Bundle ID, HL7 MSH-10, encounter ID, or specimen barcode. */
        @Column(name = "external_correlation_id", length = 120)
        private String externalCorrelationId;

        @Column(name = "current_stage", nullable = false, length = 64)
        private String currentStage;

        /** Clinical urgency tier: STAT, URGENT, ROUTINE. */
        @Column(name = "priority", nullable = false, length = 16)
        private String priority = "ROUTINE";

        /** Lifecycle status: ACTIVE, ESCALATED, COMPLETED, CANCELLED. */
        @Column(name = "status", nullable = false, length = 24)
        private String status = "ACTIVE";

        /** JSON metadata indexed on PostgreSQL via functional GIN index for fast attribute filtering. */
        @Column(name = "context_json", nullable = false, length = org.hibernate.Length.LONG32)
        private String contextJson = "{}";

        @Column(name = "sla_deadline_at", nullable = false)
        private Instant slaDeadlineAt;

        @Column(name = "started_at", nullable = false)
        private Instant startedAt = Instant.now();

        @Column(name = "completed_at")
        private Instant completedAt;

        /** Optimistic locking prevents concurrent ward/lab/pharmacy transitions from clobbering stage state. */
        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getWorkflowCode() { return workflowCode; }
        public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }
        public int getDefinitionVersion() { return definitionVersion; }
        public void setDefinitionVersion(int definitionVersion) { this.definitionVersion = definitionVersion; }
        public UUID getSubjectPatientId() { return subjectPatientId; }
        public void setSubjectPatientId(UUID subjectPatientId) { this.subjectPatientId = subjectPatientId; }
        public String getExternalCorrelationId() { return externalCorrelationId; }
        public void setExternalCorrelationId(String externalCorrelationId) { this.externalCorrelationId = externalCorrelationId; }
        public String getCurrentStage() { return currentStage; }
        public void setCurrentStage(String currentStage) { this.currentStage = currentStage; }
        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getContextJson() { return contextJson; }
        public void setContextJson(String contextJson) { this.contextJson = contextJson; }
        public Instant getSlaDeadlineAt() { return slaDeadlineAt; }
        public void setSlaDeadlineAt(Instant slaDeadlineAt) { this.slaDeadlineAt = slaDeadlineAt; }
        public Instant getStartedAt() { return startedAt; }
        public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
        public Instant getCompletedAt() { return completedAt; }
        public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
        public long getVersion() { return version; }
    }

    /**
     * Immutable historical transition record establishing medico-legal chain of custody
     * for every clinical sign-off, handoff, verification, or HIE dispatch.
     */
    @Entity
    @Table(name = "hc_workflow_step_logs")
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class WorkflowStepLogEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "instance_id", nullable = false)
        private UUID instanceId;

        @Column(name = "from_stage", length = 64)
        private String fromStage;

        @Column(name = "to_stage", nullable = false, length = 64)
        private String toStage;

        @Column(name = "actor_id", nullable = false, length = 120)
        private String actorId;

        @Column(name = "actor_role", nullable = false, length = 64)
        private String actorRole;

        @Column(name = "notes", length = org.hibernate.Length.LONG32)
        private String notes;

        @Column(name = "transition_metadata_json", length = org.hibernate.Length.LONG32)
        private String transitionMetadataJson;

        @Column(name = "transitioned_at", nullable = false)
        private Instant transitionedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getInstanceId() { return instanceId; }
        public void setInstanceId(UUID instanceId) { this.instanceId = instanceId; }
        public String getFromStage() { return fromStage; }
        public void setFromStage(String fromStage) { this.fromStage = fromStage; }
        public String getToStage() { return toStage; }
        public void setToStage(String toStage) { this.toStage = toStage; }
        public String getActorId() { return actorId; }
        public void setActorId(String actorId) { this.actorId = actorId; }
        public String getActorRole() { return actorRole; }
        public void setActorRole(String actorRole) { this.actorRole = actorRole; }
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
        public String getTransitionMetadataJson() { return transitionMetadataJson; }
        public void setTransitionMetadataJson(String transitionMetadataJson) { this.transitionMetadataJson = transitionMetadataJson; }
        public Instant getTransitionedAt() { return transitionedAt; }
        public void setTransitionedAt(Instant transitionedAt) { this.transitionedAt = transitionedAt; }
    }
}
