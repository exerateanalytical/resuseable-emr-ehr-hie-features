# OPES HealthCore — Cameroon, CEMAC & Regional Regulatory Adapter Guide

Per Directive Section 9 ("Country-Specific and Regional Adapters"), OPES HealthCore keeps country-specific billing, terminology, public health reporting, and regulatory rules strictly inside **pluggable adapter configurations** rather than hard-coding them into core domain entities.

---

## 1. Cameroon / CEMAC Reference Regional Profile (`profile: cm-cemac`)

| Capability Area | Cameroon / CEMAC Localization Pattern | HealthCore Extension / Configuration Point |
|---|---|---|
| **Currency & Taxation** | Central African CFA Franc (`XAF`, 0 decimal minor units), VAT exemptions for essential medicines on the MINSANTE Essential Medicines List | `PharmacyCommerceEntities.ProductEntity.currency = "XAF"`, `UhcBillingProperties.defaultCurrency=XAF`, `PharmacyInventoryValuationSpi` |
| **Bilingual Support (EN / FR)** | Mandatory English and French labels for clinical forms, patient receipts, prescriptions, and CoA printouts | Spring `MessageSource` bundles (`messages_en.properties`, `messages_fr.properties`), `SpecialtyExtensionContract` localized prompts |
| **UHC & Payer Ecosystem** | Cameroon Universal Health Coverage (CSU / Couverture Santé Universelle) voucher & maternal/child packages, CNPS, private mutuals, MTN MoMo & Orange Money settlement | `healthcore-starter-uhc-billing` (`PaymentRailSpi`), `healthcore-starter-revenue-coding-claims` |
| **Public Health & Surveillance** | MINSANTE / SNIS DHIS2 monthly aggregate indicators & IDSR weekly notifiable disease feeds (malaria, cholera, measles, meningitis, VHF) | `healthcore-starter-disease-surveillance` (`Dhis2ExportAdapter`) |
| **National Medicines Regulation (DPML / LANACOME)** | Controlled-substance narcotic registers, batch lot/expiry FEFO enforcement, pharmacovigilance adverse-event flagging, pharmaceutical batch CoA & recall traceability | `healthcore-starter-pharmacy-commerce`, `healthcore-starter-pharma-qms`, `healthcore-starter-pharma-serialization` |
| **Civil Registration (BUNEC / CRVS)** | WHO MCCOD structured death cause coding and birth notification payload exports | `healthcore-starter-crvs-mortuary` |
| **Connectivity & Power Resilience** | Offline-first store-and-forward sync for district clinics, rural pharmacies, and collection points with intermittent connectivity | `healthcore-starter-offline-sync` + `healthcore-mobile-kmp` |

---

## 2. How to Add a New Country or Regional Adapter

1. Create a lightweight Spring Boot starter (e.g., `healthcore-adapter-nigeria`, `healthcore-adapter-kenya`, `healthcore-adapter-eu`) that depends only on `healthcore-persistence-core` and the target domain starter.
2. Implement the relevant SPI beans (`ElectronicSignatureSpi`, `PharmacyInventoryValuationSpi`, `CriticalValueEscalationSpi`, `EhrProvenanceReconciliationContract`, or regional payment/FHIR/DHIS2 exporters).
3. Register the adapter via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — zero edits to core HealthCore modules required.

---

## 3. Standards Verification Status Model (Directive Section 9)

OPES HealthCore strictly tracks every international standard across 5 explicit maturity stages so no consumer mistakes technical enablement for regulatory certification:

1. **Standard Identified** — Applicable scope and boundary mapped to specific packs/modules.
2. **Implementation in Progress** — Schema, entities, or API stubs partially built.
3. **Technical Implementation Complete** — Full domain model, state guards, Liquibase migrations, and unit/contract tests implemented and statically verified in the repository.
4. **Automated Conformance Evidence Available** — Executed in live JDK 25 + Testcontainers / HL7 FHIR validator / schema validation CI pipeline with archived test artifacts.
5. **Independently Validated / Certified** — Audited by an accredited external body (e.g., ISO 15189 accreditation body, WHO GMP / EU GMP regulatory inspectorate, IRB/GCP sponsor audit) on a specific deployed customer instance.

> **Mandatory Legal & Quality Notice**: Installing OPES HealthCore modules provides **technical controls** (audit trails, maker-checker e-signatures, IQC/calibration/consent blocks, structured exports). It does **not** by itself grant ISO 15189 accreditation, ISO 20387 certification, WHO/EU GMP compliance, FDA 21 CFR Part 11 validation, or HIPAA/GDPR legal compliance. Adopting organizations must execute site-level IQ/OQ/PQ validation, SOP governance, and accredited audits.
