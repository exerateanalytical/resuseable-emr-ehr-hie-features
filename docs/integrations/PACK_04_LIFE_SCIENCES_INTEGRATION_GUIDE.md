# Pack 04 Integration Guide — Life Sciences, Biobanking, Genomics & Clinical Research (`healthcore-pack-life-sciences`)

1. **Purpose & Scope**: Academic research institutes, biotech R&D labs, ISO 20387 biobanks, genomics core facilities, and ICH E6(R3)/CDISC clinical trial sites/CROs. **Scientific samples and pseudonymous trial subjects are strictly decoupled from clinical EMR patients.**
2. **Target Use Cases**: Research LIMS & signed ELN notebooks, ISO 20387 biospecimen freezer/aliquot/MTA management, sequencing Q30 QC & bioinformatics provenance, and ICH E6(R3) eConsent/eCRF/SAE/CDISC SDTM export.
3. **Included vs Excluded Modules**: Includes `healthcore-starter-life-sciences-lims`, `healthcore-starter-biobanking`, `healthcore-starter-genomics-research`, `healthcore-starter-clinical-research`. Strictly excludes MPI, EMR, hospital ops, billing, pharmacy, and mortuary.
4. **Prerequisites**: Java 25, Spring Boot 4.0, Maven 3.9+, PostgreSQL 16+ / SQL Server 2022 / MySQL 8.4+.
5. **Maven Setup**: Install `healthcore-pack-life-sciences` for the complete suite, or install ONLY a single starter (e.g., ONLY `healthcore-starter-biobanking` for a dedicated biobank).
6. **Database & Liquibase Setup**: Use `classpath:db/changelog/packs/pack-life-sciences-master.xml` (or the individual starter changelog for single-module apps).
7. **Required Environment Variables**: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `HEALTHCORE_OIDC_ISSUER_URI`.
8. **Security & Research Governance**: OIDC JWT + `X-Facility-Id`; ethics/IRB approval gate before sample registration; donor consent verification & revocation; maker-checker ELN locking; PI eCRF signature lock.
9. **Core REST Endpoints**:
   - LIMS/ELN: `/api/v1/lifesciences/lims/{studies,samples,eln}`
   - Biobank: `/api/v1/lifesciences/biobank/{specimens,freezers,donors,mta}`
   - Genomics: `/api/v1/lifesciences/genomics/{runs,datasets}`
   - Clinical Trials: `/api/v1/lifesciences/clinical-research/{subjects/enroll,ecrf,safety-events,protocols/{code}/export/cdisc-sdtm}`
10. **End-to-End Sample Workflow**: Approve study Ethics/IRB -> register scientific samples (`CELL_LINE`, `DONOR_PSEUDONYM`, `ENVIRONMENTAL`) -> lock signed ELN -> store biobank aliquots -> verify sequencing Q30 QC -> catalog VCF provenance -> export de-identified CDISC SDTM.
11. **Java Service Injection**: Inject `LifeSciencesLimsService`, `BiobankingService`, `GenomicsResearchService`, or `ClinicalResearchService`.
12. **Extension Points (SPIs)**: `ResearchInstrumentAdapterSpi`, `VariantStoreAdapterSpi` (GA4GH `htsget`/OpenCGA/Hail), and `ElectronicSignatureSpi`.
13. **Domain Events & Audit Trail**: Full `@ClinicalAudit` immutable audit trail across study activation, custody transfer, consent withdrawal, MTA shipment, and eCRF lock.
14. **De-Identification & Pseudonymization**: Trial subjects and biobank donors use pseudonyms (`USUBJID`, `donorPseudonym`), never raw clinical PII.
15. **Standards Alignment**: Technical capabilities aligned with ISO 20387 (biobanking), ICH E6(R3) GCP, and CDISC (`CDASH`/`SDTM`/`ADaM`); institutional governance and IRB approval remain required.
16. **Minimal Working Examples**: `examples/life-sciences-lims`, `examples/biobank`, and `examples/clinical-research`.
17. **Smoke Verification Commands**: Run any of the 3 life-sciences examples independently with `mvn spring-boot:run`.
18. **Testing Strategy**: `LifeSciencesLimsServiceTest`, `BiobankingServiceTest`, `GenomicsResearchServiceTest`, `ClinicalResearchServiceTest`, `CapabilityPackIsolationTest`.
19. **Common Pitfalls & Troubleshooting**: Registering samples without active Ethics/IRB approval, shipping MTA after donor consent withdrawal, cataloging datasets from `Q30 < 80%` runs, or locking eCRFs with open queries all throw `IllegalStateException`.
20. **Upgrade & Compatibility Notes**: Optionally add `healthcore-starter-deidentification-research` when bridging hospital EHR cohorts into research datasets.
