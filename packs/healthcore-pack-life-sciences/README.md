# `healthcore-pack-life-sciences` (Capability Pack 04)

Independent life sciences pack for Research LIMS/ELN, ISO 20387 Biobanking, Genomics Metadata, and ICH E6(R3)/CDISC Clinical Research EDC.

- **Included Modules**: `healthcore-starter-life-sciences-lims`, `healthcore-starter-biobanking`, `healthcore-starter-genomics-research`, `healthcore-starter-clinical-research` (+ core/security)
- **Strictly Excluded**: MPI, EMR, hospital operations, billing, pharmacy commerce, mortuary
- **Liquibase Master**: `classpath:db/changelog/packs/pack-life-sciences-master.xml`
- **Integration Guide**: `docs/integrations/PACK_04_LIFE_SCIENCES_INTEGRATION_GUIDE.md`
- **Standalone Examples**: `examples/life-sciences-lims`, `examples/biobank`, `examples/clinical-research`
