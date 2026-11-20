# `healthcore-pack-pharma-manufacturing` (Capability Pack 03)

Independent pharmaceutical manufacturing, QMS/QC/CAPA/CoA, and GS1 serialization/GDP cold-chain pack.

- **Included Modules**: `healthcore-starter-pharma-manufacturing`, `healthcore-starter-pharma-qms`, `healthcore-starter-pharma-serialization` (+ core/security)
- **Strictly Excluded**: MPI, EMR, hospital operations, inpatient ADT, surgery/OT, mortuary
- **Liquibase Master**: `classpath:db/changelog/packs/pack-pharma-manufacturing-master.xml`
- **Integration Guide**: `docs/integrations/PACK_03_PHARMA_MANUFACTURING_INTEGRATION_GUIDE.md`
- **Standalone Example**: `examples/pharma-manufacturing`
