# `healthcore-pack-ehr` (Capability Pack 06)

Longitudinal multi-facility EHR capability pack extending `healthcore-pack-emr` with cross-facility provenance, FHIR R4 / IPS portability, patient portal, and clinical document templates.

- **Included Modules**: `healthcore-pack-emr`, `hie-interop`, `fhir-hie`, `patient-portal`, `templates-dashboards`
- **Liquibase Master**: `classpath:db/changelog/packs/pack-ehr-master.xml`
- **Integration Guide**: `docs/integrations/PACK_06_LONGITUDINAL_EHR_INTEGRATION_GUIDE.md`
- **Standalone Example**: `examples/longitudinal-ehr`
