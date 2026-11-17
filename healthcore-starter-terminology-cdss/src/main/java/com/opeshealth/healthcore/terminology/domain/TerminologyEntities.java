/*
 * =============================================================================
 * Opes HealthCore SDK — Terminology & CDSS JPA Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.terminology.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * Container class grouping normalized clinical terminology and CDSS rule entities
 * (LOINC, SNOMED CT, ICD-10/11, RxNorm/ATC, UCUM, concept maps, and DDI rules).
 */
public final class TerminologyEntities {

    private TerminologyEntities() {}

    /** Canonical coded concept entry across LOINC, SNOMED CT, ICD-10, ICD-11, RxNorm, ATC, and CVX. */
    @Entity
    @Table(name = "hc_code_systems")
    public static class CodeConceptEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "system_uri", nullable = false, length = 200)
        private String systemUri;

        @Column(name = "code", nullable = false, length = 64)
        private String code;

        @Column(name = "display_en", nullable = false, length = 300)
        private String displayEn;

        @Column(name = "display_fr", length = 300)
        private String displayFr;

        @Column(name = "clinical_domain", length = 64)
        private String clinicalDomain;

        @Column(name = "ucum_unit", length = 32)
        private String ucumUnit;

        @Column(name = "active", nullable = false)
        private boolean active = true;

        public UUID getId() { return id; }
        public String getSystemUri() { return systemUri; }
        public void setSystemUri(String systemUri) { this.systemUri = systemUri; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getDisplayEn() { return displayEn; }
        public void setDisplayEn(String displayEn) { this.displayEn = displayEn; }
        public String getDisplayFr() { return displayFr; }
        public void setDisplayFr(String displayFr) { this.displayFr = displayFr; }
        public String getClinicalDomain() { return clinicalDomain; }
        public void setClinicalDomain(String clinicalDomain) { this.clinicalDomain = clinicalDomain; }
        public String getUcumUnit() { return ucumUnit; }
        public void setUcumUnit(String ucumUnit) { this.ucumUnit = ucumUnit; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    /** Precomputed ancestor-descendant closure row replacing slow recursive CTEs for IS-A checks. */
    @Entity
    @Table(name = "hc_concept_closure")
    public static class ConceptClosureEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "system_uri", nullable = false, length = 200)
        private String systemUri;

        @Column(name = "ancestor_code", nullable = false, length = 64)
        private String ancestorCode;

        @Column(name = "descendant_code", nullable = false, length = 64)
        private String descendantCode;

        @Column(name = "depth", nullable = false)
        private int depth;

        public UUID getId() { return id; }
        public String getSystemUri() { return systemUri; }
        public void setSystemUri(String systemUri) { this.systemUri = systemUri; }
        public String getAncestorCode() { return ancestorCode; }
        public void setAncestorCode(String ancestorCode) { this.ancestorCode = ancestorCode; }
        public String getDescendantCode() { return descendantCode; }
        public void setDescendantCode(String descendantCode) { this.descendantCode = descendantCode; }
        public int getDepth() { return depth; }
        public void setDepth(int depth) { this.depth = depth; }
    }

    /** Bidirectional or directional terminology map (e.g., SNOMED CT diagnosis -> ICD-10 billing code). */
    @Entity
    @Table(name = "hc_concept_maps")
    public static class ConceptMapEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "source_system", nullable = false, length = 200)
        private String sourceSystem;

        @Column(name = "source_code", nullable = false, length = 64)
        private String sourceCode;

        @Column(name = "target_system", nullable = false, length = 200)
        private String targetSystem;

        @Column(name = "target_code", nullable = false, length = 64)
        private String targetCode;

        @Column(name = "equivalence", nullable = false, length = 32)
        private String equivalence;

        public UUID getId() { return id; }
        public String getSourceSystem() { return sourceSystem; }
        public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }
        public String getSourceCode() { return sourceCode; }
        public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }
        public String getTargetSystem() { return targetSystem; }
        public void setTargetSystem(String targetSystem) { this.targetSystem = targetSystem; }
        public String getTargetCode() { return targetCode; }
        public void setTargetCode(String targetCode) { this.targetCode = targetCode; }
        public String getEquivalence() { return equivalence; }
        public void setEquivalence(String equivalence) { this.equivalence = equivalence; }
    }

    /** Evidence-based Drug-Drug Interaction, Drug-Allergy, or Drug-Condition rule. */
    @Entity
    @Table(name = "hc_ddi_rules")
    public static class DdiRuleEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "drug_a_code", nullable = false, length = 64)
        private String drugACode;

        @Column(name = "drug_b_or_condition_code", nullable = false, length = 64)
        private String drugBOrConditionCode;

        /** DDI, DRUG_ALLERGY_CLASS, CONTRAINDICATION, or DUPLICATE_THERAPY. */
        @Column(name = "rule_type", nullable = false, length = 32)
        private String ruleType;

        /** CRITICAL, WARNING, or INFO. */
        @Column(name = "severity", nullable = false, length = 16)
        private String severity;

        @Column(name = "clinical_message", nullable = false, length = 600)
        private String clinicalMessage;

        @Column(name = "recommended_action", length = 400)
        private String recommendedAction;

        public UUID getId() { return id; }
        public String getDrugACode() { return drugACode; }
        public void setDrugACode(String drugACode) { this.drugACode = drugACode; }
        public String getDrugBOrConditionCode() { return drugBOrConditionCode; }
        public void setDrugBOrConditionCode(String drugBOrConditionCode) { this.drugBOrConditionCode = drugBOrConditionCode; }
        public String getRuleType() { return ruleType; }
        public void setRuleType(String ruleType) { this.ruleType = ruleType; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public String getClinicalMessage() { return clinicalMessage; }
        public void setClinicalMessage(String clinicalMessage) { this.clinicalMessage = clinicalMessage; }
        public String getRecommendedAction() { return recommendedAction; }
        public void setRecommendedAction(String recommendedAction) { this.recommendedAction = recommendedAction; }
    }
}
