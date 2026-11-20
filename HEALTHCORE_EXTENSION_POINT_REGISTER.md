# OPES HealthCore — Extension Point (SPI) Register

Consumer applications customize OPES HealthCore behavior cleanly by registering Spring `@Bean` implementations of these interfaces—without forking internal starter code.

| SPI Interface | Module | Domain / Pack | Default Behavior | Typical Consumer Override |
|---|---|---|---|---|
| `ElectronicSignatureSpi` | `healthcore-persistence-core` | Cross-industry (Packs 03, 04, 07) | SHA-256 payload + signature digest binding with maker-checker segregation of duties | Hardware Security Module (HSM), X.509 PKI, or OIDC step-up MFA provider |
| `PharmacyDispensingPolicySpi` | `healthcore-starter-pharmacy-commerce` | Pack 01 (Pharmacy) | Positive qty check + independent second witness on controlled substances | Country generic substitution rules, e-Rx signature checks, payer copay validation |
| `DiagnosticsQcPolicySpi` | `healthcore-starter-diagnostics-core` | Pack 02 (Diagnostics) | Blocks release if order status is `REJECTED_RECOLLECT` | Westgard multi-rule QC validation, modality QA calibration gates |
| `PharmaBatchReleasePolicySpi` | `healthcore-starter-pharma-manufacturing` | Pack 03 (Pharma Mfg) | Requires line clearance verified + IPC checks passed + yield within tolerance | EU Qualified Person (QP) release rules, stability chamber checks, ERP MRP sync |
| `PharmaSerializationProviderSpi` | `healthcore-starter-pharma-serialization` | Pack 03 (Serialization) | Deterministic random serial prefix generator (`SN-...`) | EU FMD EMVS, US DSCSA VRS, or GS1 EPCIS serial pool allocator |
| `ResearchInstrumentAdapterSpi` | `healthcore-starter-life-sciences-lims` | Pack 04 (LIMS/ELN) | Raw JSON payload storage | Vendor-specific LC-MS, qPCR, ELISA, or flow-cytometry raw output parsers |
| `VariantStoreAdapterSpi` | `healthcore-starter-genomics-research` | Pack 04 (Genomics) | Validates non-blank URI and 64-hex SHA-256 digest | GA4GH `htsget`, OpenCGA, Hail, or S3/GCS object-integrity verifier |
| `WorkforceCredentialSpi` | `healthcore-starter-workforce-roster` | Pack 07 (HIS) | Local license expiry validation | National medical/nursing council license registry lookup |
| `PaymentGatewaySpi` / `PayerClaimAdapterSpi` | `healthcore-starter-uhc-billing`, `revenue-coding-claims` | Packs 01, 05, 07 | Local UHC/CBHI voucher & double-entry posting | Mobile Money (MTN/Orange), Stripe, national NHIS/UHC clearinghouses |
| `ExternalHieConnectorSpi` | `healthcore-starter-integration-ops`, `hie-interop` | Packs 06, 07, 08 | Transactional outbox + DLQ storage | Regional OpenHIM, DHIS2, IHE XDS.b registry, or national FHIR broker |
