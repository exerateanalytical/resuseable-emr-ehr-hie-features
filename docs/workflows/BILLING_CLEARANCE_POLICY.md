# OPES HealthCore — Configurable Billing & Eligibility Clearance Policy

## 1. Zero Duplicate Ledger Rule
Workflow payment and coverage clearance **reuses** existing billing and claims modules (`healthcore-starter-uhc-billing`, `healthcore-starter-revenue-coding-claims`). `healthcore-starter-workflow-engine` does **not** create a second financial ledger; it evaluates clearance decisions via the pluggable `WorkflowBillingClearancePolicySpi` contract.

## 2. Supported Clearance Outcomes (All 11 Outcomes)

| `PaymentClearanceOutcome` | Permits Fulfillment Activation? | Typical Clinical / Operational Trigger |
|---|---|---|
| `PAYMENT_NOT_REQUIRED` | Yes | Facility policy waives prepayment for category or zero-fee service |
| `PAID` | Yes | Full payment confirmed by cashier / mobile money / card receipt |
| `INSURANCE_AUTHORIZED` | Yes | Payer/UHC/CBHI prior authorization verified |
| `CREDIT_APPROVED` | Yes | Approved corporate or facility credit account |
| `SPONSORED` | Yes | Vertical public health program / NGO / government sponsor |
| `DEFERRED` | Yes | Authorized post-service settlement agreement |
| `WAIVED` | Yes | Indigent / compassionate fee waiver |
| `EMERGENCY_OVERRIDE` | Yes | **Mandatory safety bypass** for emergency stabilization, ESI 1–2 triage, or critical-finding communication |
| `PAYMENT_PENDING` | **No** | Prepayment required; task held on non-destructive financial hold & routed to cashier worklist |
| `PARTIALLY_PAID` | Configurable (`allowPartialPaymentActivation`) | Deposit recorded; activates only if facility policy permits partial-payment release |
| `REJECTED_OR_EXPIRED` | **No** | Payer authorization expired/denied; clinical order preserved pending resolution |

## 3. Non-Negotiable Clinical Safety Invariants
1. **Emergency Stabilization Bypass:** `DefaultFacilityBillingClearancePolicy` guarantees that whenever `emergencyUrgent == true`, `criticalFindingCommunication == true`, `serviceCategory == EMERGENCY_STABILIZATION`, or `serviceCategory == CRITICAL_RESULT_COMMUNICATION`, the policy **always** returns `EMERGENCY_OVERRIDE` (`fulfillmentEligible = true`, `routeToCashierWorklist = false`), regardless of unpaid balance or strict prepayment settings.
2. **Non-Destructive Financial Holds:** Financial holds **never** cancel, delete, or mutate signed clinical orders (`preserveUnderlyingClinicalOrder = true`). The order remains durably persisted in `hc_order_routing_tasks` with `routing_status = ROUTED_AWAITING_CLEARANCE` until payment or authorization arrives.
3. **Replay-Safe Payment Release:** `PatientJourneyOrchestratorService.confirmPaymentAndReleaseOrder` records `paymentIdempotencyKey` in `hc_workflow_processed_events` (`UNIQUE(facility_id, idempotency_key)`). Replaying the same payment webhook or outbox event never duplicates orders, tasks, or `ServiceCleared` events.

## 4. Facility-Level Configuration (`FacilityBillingPolicyConfig`)
Each facility independently configures:
- `consultationPrepayRequired`
- `laboratoryPrepayRequired`
- `imagingPrepayRequired`
- `pharmacyPrepayRequired`
- `allowPartialPaymentActivation`
- `consolidatedBillingEnabled`

Consuming applications can supply a custom Spring `@Bean` implementing `WorkflowBillingClearancePolicySpi` (for example, integrating real-time national UHC/CBHI eligibility APIs); `WorkflowEngineAutoConfiguration` backs off cleanly via `@ConditionalOnMissingBean`.
