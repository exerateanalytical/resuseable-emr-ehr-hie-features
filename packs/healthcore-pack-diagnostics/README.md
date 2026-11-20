# `healthcore-pack-diagnostics` (Capability Pack 02)

Modality-neutral ISO 15189-aligned diagnostic center pack supporting independent medical laboratories, independent radiology centers, or combined diagnostic facilities.

- **Base Modules**: `healthcore-persistence-core`, `healthcore-security-audit`, `healthcore-starter-diagnostics-core`
- **Selectable Profiles**: `lab-only` (`lis-diagnostics`, zero radiology), `radiology-only` (`radiology`, zero LIS), `combined-diagnostics`
- **Liquibase Master**: `classpath:db/changelog/packs/pack-diagnostics-master.xml`
- **Integration Guide**: `docs/integrations/PACK_02_DIAGNOSTICS_INTEGRATION_GUIDE.md`
- **Standalone Examples**: `examples/diagnostic-laboratory`, `examples/radiology-center`
