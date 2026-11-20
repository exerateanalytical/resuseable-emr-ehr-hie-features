# Pack 02 Integration Guide — Diagnostic Centers: Laboratory & Radiology (`healthcore-pack-diagnostics`)

1. **Purpose & Scope**: Independent medical laboratories, radiology/imaging centers, or combined diagnostic centers aligned with ISO 15189 technical controls (technical support only — accreditation requires institutional QMS assessment).
2. **Target Use Cases**: Referring partner registration, test/exam accessioning, specimen rejection & recollection, Turnaround-Time (TAT) SLA tracking, ISO 15189 quality incidents, plus independent LIS analyzer integration OR Radiology RIS/DICOMweb integration.
3. **Included vs Excluded Modules**: Base pack includes `healthcore-persistence-core`, `healthcore-security-audit`, `healthcore-starter-diagnostics-core`. Add ONLY `healthcore-starter-lis-diagnostics` (lab-only), ONLY `healthcore-starter-radiology` (radiology-only), or both. Strictly excludes EMR, inpatient ADT, pharmacy commerce, surgery, and pharma manufacturing.
4. **Prerequisites**: Java 25, Spring Boot 4.0, Maven 3.9+, PostgreSQL 16+ / SQL Server 2022 / MySQL 8.4+.
5. **Maven Setup (Lab-Only vs Radiology-Only)**:
```xml
<!-- Modality-neutral base -->
<dependency>
    <groupId>com.opeshealth.healthcore</groupId>
    <artifactId>healthcore-pack-diagnostics</artifactId>
</dependency>
<!-- Choose ONLY what you operate: -->
<!-- Lab-only: healthcore-starter-lis-diagnostics -->
<!-- Radiology-only: healthcore-starter-radiology -->
```
6. **Database & Liquibase Setup**: Use `classpath:db/changelog/packs/pack-diagnostics-master.xml` plus your chosen modality changelog (`db/changelog/pharmacy_lis/001-pharmacy-lis.xml` OR `db/changelog/radiology/001-radiology-ris.xml`).
7. **Required Environment Variables**: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `HEALTHCORE_OIDC_ISSUER_URI`.
8. **Security & Multi-Tenant Context**: OIDC JWT + `X-Facility-Id`; authorities `PERM_lab:order:create`, `PERM_lab:result:enter`, `PERM_lab:verify`, `PERM_radiology:report`.
9. **Core REST Endpoints**:
   - `POST /api/v1/diagnostics-core/referring-partners`
   - `POST /api/v1/diagnostics-core/orders`
   - `POST /api/v1/diagnostics-core/orders/{id}/reject?reason=...`
   - `POST /api/v1/diagnostics-core/orders/{id}/verify-release`
   - `POST /api/v1/diagnostics-core/quality-incidents`
10. **End-to-End Sample Workflow**: Register referring clinic -> accession lab/imaging order with TAT SLA -> reject hemolyzed specimen or verify & release report with automated TAT breach flag -> log ISO 15189 pre-analytical/analytical/post-analytical incident.
11. **Java Service Injection**: Inject `DiagnosticsCoreService`, plus `RadiologyRisService` (radiology) or LIS services (lab).
12. **Extension Points (SPIs)**: Implement `DiagnosticsCoreService.DiagnosticsQcPolicySpi` for Westgard QC rules or modality calibration release gates.
13. **Domain Events & Audit Trail**: `@ClinicalAudit` captures partner registration, order creation, specimen rejection, report verification, and quality incidents.
14. **Instrument & PACS Integration**: Add `healthcore-starter-hl7-astm-dicom` for ASTM/HL7 v2 lab analyzers or DICOMweb/MWL/PACS gateways.
15. **Standards & Interoperability**: ISO 15189-oriented quality tracking, LOINC/CPT catalog codes, HL7 v2 ORU/ORM, ASTM, DICOMweb.
16. **Minimal Working Examples**: `examples/diagnostic-laboratory` (zero radiology) and `examples/radiology-center` (zero LIS).
17. **Smoke Verification Commands**: Run either example independently with `mvn spring-boot:run`.
18. **Testing Strategy**: `DiagnosticsCoreServiceTest`, `RadiologyRisServiceTest`, and `CapabilityPackIsolationTest`.
19. **Common Pitfalls & Troubleshooting**: Attempting to verify/release a `REJECTED_RECOLLECT` order throws `IllegalStateException`; blank rejection reason throws `IllegalArgumentException`.
20. **Upgrade & Compatibility Notes**: Adding radiology later to a lab-only center (or vice versa) requires zero breaking schema changes.
