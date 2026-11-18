# Opes HealthCore SDK — Reusable Workflow Assembly & PostgreSQL 16+ Guide

**Author / Principal Architect:** Jude Nshome — **Opes Health Systems** (`nshome@opesware.com`)  
**Website:** [https://www.opeshealthsystems.com](https://www.opeshealthsystems.com) | **Location:** Douala, Cameroon  
**Stack:** 100% Pure **Java 25 + Spring Boot 4.0 + PostgreSQL 16+** (Zero TypeScript)

---

## 1. Assembling Any Healthcare System in Under 1 Hour (Maven Starter Matrix)

Every Opes HealthCore starter is composable. Import `healthcore-bom` once in `<dependencyManagement>`, then pick only the starters your product requires:

| Target Product | Exact Maven Starters to Declare | Workflows Automatically Ready (`WorkflowOrchestrationService`) |
| :--- | :--- | :--- |
| **Full Hospital EMR** | `healthcore-starter-emr-clinical`, `healthcore-starter-workflow-engine`, `healthcore-starter-pharmacy-inventory`, `healthcore-starter-lis-diagnostics`, `healthcore-starter-blood-bank`, `healthcore-starter-surgery-ot`, `healthcore-starter-uhc-billing`, `healthcore-starter-templates-dashboards`, `healthcore-starter-offline-sync` | `OPD_CONSULTATION_FLOW`, `INPATIENT_ADT_CARE_FLOW`, `SURGICAL_OT_FLOW`, `PHARMACY_DISPENSE_FLOW`, `DIAGNOSTIC_LAB_FLOW`, `CLOSED_LOOP_REFERRAL_FLOW` |
| **Longitudinal EHR Network** | `healthcore-starter-mpi`, `healthcore-starter-terminology-cdss`, `healthcore-starter-workflow-engine`, `healthcore-starter-emr-clinical`, `healthcore-starter-fhir-hie`, `healthcore-starter-offline-sync` | `OPD_CONSULTATION_FLOW`, `CLOSED_LOOP_REFERRAL_FLOW`, `EHR_HIE_EXCHANGE_FLOW` |
| **Regional / National HIE** | `healthcore-starter-mpi`, `healthcore-starter-terminology-cdss`, `healthcore-starter-workflow-engine`, `healthcore-starter-fhir-hie`, `healthcore-starter-hl7-astm-dicom`, `healthcore-starter-interoperability-ihe`, `healthcore-starter-disease-surveillance`, `healthcore-starter-deidentification-research`, `healthcore-starter-api-product` | `EHR_HIE_EXCHANGE_FLOW`, `CLOSED_LOOP_REFERRAL_FLOW`, `CHW_OUTBREAK_ESCALATION_FLOW` |
| **Standalone Pharmacy (PIS)** | `healthcore-starter-mpi`, `healthcore-starter-terminology-cdss`, `healthcore-starter-workflow-engine`, `healthcore-starter-pharmacy-inventory`, `healthcore-starter-uhc-billing`, `healthcore-starter-offline-sync` | `PHARMACY_DISPENSE_FLOW` |
| **Standalone Lab (LIS)** | `healthcore-starter-mpi`, `healthcore-starter-terminology-cdss`, `healthcore-starter-workflow-engine`, `healthcore-starter-lis-diagnostics`, `healthcore-starter-hl7-astm-dicom`, `healthcore-starter-uhc-billing` | `DIAGNOSTIC_LAB_FLOW` |
| **Specialty Clinic (Dental / Eye / Cardiac / Pediatrics / Derm)** | `healthcore-starter-emr-clinical`, `healthcore-starter-workflow-engine`, `healthcore-starter-uhc-billing`, `healthcore-starter-templates-dashboards`, `healthcore-starter-offline-sync` | `OPD_CONSULTATION_FLOW`, `CLOSED_LOOP_REFERRAL_FLOW` (plus active `SpecialtyPackSpi`) |
| **CHW, Home Care & UHC Watch** | `healthcore-starter-mpi`, `healthcore-starter-workflow-engine`, `healthcore-starter-chw-homecare`, `healthcore-starter-programs-immunization`, `healthcore-starter-disease-surveillance`, `healthcore-starter-uhc-billing`, `healthcore-starter-offline-sync` | `CHW_OUTBREAK_ESCALATION_FLOW`, `CLOSED_LOOP_REFERRAL_FLOW` |

---

## 2. Customizing & Extending Clinical/HIE Workflows in Java 25

### A. Override Any State Machine Per Facility via REST or Code
Facilities can publish versioned state machines without touching Java code via `POST /api/v1/workflows/definitions`, or call `workflowOrchestrationService.registerCustomDefinition(...)`.

### B. Inject Pluggable Clinical/Compliance Gates via `WorkflowStepHookSpi`
Any Spring `@Component` implementing `WorkflowStepHookSpi` automatically plugs into `WorkflowOrchestrationService` in deterministic `order()` inside the ACID transaction:

```java
@Component
public class ControlledNarcoticWitnessGate implements WorkflowSpi.WorkflowStepHookSpi {

    @Override
    public boolean supportsWorkflow(String workflowCode) {
        return ReusableWorkflowCatalog.PHARMACY_DISPENSE_FLOW.equals(workflowCode);
    }

    @Override
    public void beforeTransition(WorkflowSpi.WorkflowTransitionContext ctx) {
        if ("DISPENSED_AND_CLAIMED".equals(ctx.toStage())
                && Boolean.TRUE.equals(ctx.metadata().get("controlledSchedule"))
                && !ctx.metadata().containsKey("witnessId")) {
            throw new IllegalStateException("Controlled substance dispense requires a secondary pharmacist witnessId");
        }
    }
}
```

### C. SLA Breach Escalation Listeners (`WorkflowSlaEscalationSpi`)
`WorkflowOrchestrationService.escalateOverdueSlaInstances()` polls overdue `ACTIVE` workflows (backed by PostgreSQL partial index `idx_hc_wf_active_sla`), promotes them to `ESCALATED`, writes `WorkflowSlaBreachedEvent` to `hc_outbox_events`, and invokes every `WorkflowSlaEscalationSpi` bean.

---

## 3. PostgreSQL 16+ Production Optimizations

Opes HealthCore runs **PostgreSQL 16+ as primary engine** (`spring.profiles.active: postgres,keycloak`):
1. **Fuzzy Trigram Search (`pg_trgm` GIN Indexes):** `003-postgresql-optimizations.xml` enables `pg_trgm` and indexes `hc_patients(family_name, given_name)` and `hc_code_systems(display_en, display_fr)` for millisecond matching at millions of rows.
2. **Hot-Queue Partial Indexes:** Tiny partial indexes cover only `PENDING`/`FAILED` outbox rows, unoccupied ward beds (`occupied = FALSE`), open orders, active non-quarantined FEFO lots, unacknowledged critical panic labs, unresolved offline sync conflicts, and active SLA workflow deadlines.
3. **Database-Kernel Row-Level Security (RLS):**
   - Apply `healthcore-quickstart-host/postgres/enable-rls-policies.sql` using your admin PostgreSQL role.
   - Set `healthcore.postgres.rls.enabled=true` in `application.yml`.
   - Every `@Transactional` service call automatically executes `SELECT set_config('app.current_facility_id', '<uuid>', true)` via `PostgresRlsSessionAspect`.
