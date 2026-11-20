# `healthcore-pack-hie` (Capability Pack 08)

Regional and national Health Information Exchange (HIE) pack strictly isolated from inpatient, billing, pharmacy, and pharmaceutical manufacturing modules.

- **Included Modules**: `mpi`, `terminology-cdss`, `hie-interop`, `fhir-hie`, `interoperability-ihe`, `integration-ops`, `api-product` (+ core/security)
- **Liquibase Master**: `classpath:db/changelog/packs/pack-hie-master.xml`
- **Integration Guide**: `docs/integrations/PACK_08_REGIONAL_HIE_INTEGRATION_GUIDE.md`
- **Standalone Example**: `examples/regional-hie`
