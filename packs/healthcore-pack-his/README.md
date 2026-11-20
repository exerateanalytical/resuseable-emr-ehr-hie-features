# `healthcore-pack-his` (Capability Pack 07)

Composable Hospital Information System (HIS) pack with 5 selectable profiles (`outpatient-hospital`, `general-hospital`, `multispecialty`, `tertiary-referral`, `multi-facility-network`).

- **Base Modules**: `healthcore-pack-ehr`, `hospital-ops`, `emergency-nursing`, `pharmacy-lis`, `uhc-billing`
- **Strictly Excluded**: Pharmaceutical manufacturing (`pharma-*`) and life sciences (`life-sciences-lims`, `biobanking`, `genomics-research`)
- **Liquibase Master**: `classpath:db/changelog/packs/pack-his-master.xml`
- **Integration Guide**: `docs/integrations/PACK_07_COMPOSABLE_HIS_INTEGRATION_GUIDE.md`
- **Standalone Example**: `examples/hospital-his`
