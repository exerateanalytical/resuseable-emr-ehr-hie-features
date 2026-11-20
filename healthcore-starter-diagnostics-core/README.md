# `healthcore-starter-diagnostics-core`

## Overview
Modality-neutral diagnostics center operations starter aligned with ISO 15189 technical controls (pre-analytical rejection/recollection, TAT SLA monitoring, quality incidents/CAPA). Usable independently by a **laboratory-only** OR **radiology-only** deployment without cross-pulling the other modality or EMR/HIS.

- **Maven Coordinate**: `com.opeshealth.healthcore:healthcore-starter-diagnostics-core:1.0.0`
- **Dependencies**: `healthcore-persistence-core`, `healthcore-security-audit` (**Zero pharmacy, EMR, inpatient, or HIS dependencies**)
- **Liquibase Changelog**: `classpath:db/changelog/diagnostics_core/001-diagnostics-core.xml`
- **REST Base Path**: `/api/v1/diagnostics-core`

## Extension Points
- `DiagnosticsCoreService.DiagnosticsQcPolicySpi` — pluggable Westgard / modality QA release gates.
