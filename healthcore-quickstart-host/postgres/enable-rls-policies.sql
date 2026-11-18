-- =============================================================================
-- Opes HealthCore SDK — PostgreSQL 16+ Row-Level Security (RLS) Tenant Policies
-- Author / Architect : Jude Nshome — Opes Health Systems
-- Email              : nshome@opesware.com
-- Website            : https://www.opeshealthsystems.com
-- Location           : Douala, Cameroon
-- =============================================================================
-- Purpose:
--   Provides database-kernel tenant isolation complementary to Hibernate's
--   @Filter("facilityFilter"). When enabled alongside:
--       healthcore.postgres.rls.enabled=true
--   Spring AOP binds `set_config('app.current_facility_id', '<uuid>', true)`
--   inside every @Transactional unit of work.
--
-- Usage:
--   psql -h localhost -U healthcore_admin -d healthcore -f enable-rls-policies.sql
--
-- Important Production Note:
--   Run application traffic using a non-superuser role (`healthcore_app`) without
--   BYPASSRLS so PostgreSQL enforces RLS policies cleanly, while Liquibase
--   migrations and PITR backups execute under an administrative role.
-- =============================================================================

DO $$
DECLARE
    tbl TEXT;
    tenant_tables TEXT[] := ARRAY[
        'hc_patients',
        'hc_clinical_orders',
        'hc_ward_beds',
        'hc_admissions',
        'hc_care_plans',
        'hc_specialty_records',
        'hc_fluid_balance',
        'hc_shift_handovers',
        'hc_med_reconciliations',
        'hc_clinical_referrals',
        'hc_medication_batches',
        'hc_pharmacy_dispenses',
        'hc_lab_specimens',
        'hc_diagnostic_reports',
        'hc_patient_accounts',
        'hc_cbhi_policies',
        'hc_insurance_claims',
        'hc_uhc_vouchers',
        'hc_surveillance_cases',
        'hc_idsr_cif_forms',
        'hc_chronic_cascades',
        'hc_workflow_definitions',
        'hc_workflow_instances',
        'hc_workflow_step_logs',
        'hc_sync_deltas',
        'hc_sync_conflicts'
    ];
BEGIN
    FOREACH tbl IN ARRAY tenant_tables LOOP
        IF to_regclass(tbl) IS NOT NULL THEN
            EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tbl);
            EXECUTE format('DROP POLICY IF EXISTS hc_facility_isolation ON %I', tbl);
            EXECUTE format(
                'CREATE POLICY hc_facility_isolation ON %I
                 USING (
                     current_setting(''app.current_facility_id'', true) IS NULL
                     OR current_setting(''app.current_facility_id'', true) = ''''
                     OR facility_id = current_setting(''app.current_facility_id'', true)::uuid
                 )
                 WITH CHECK (
                     current_setting(''app.current_facility_id'', true) IS NULL
                     OR current_setting(''app.current_facility_id'', true) = ''''
                     OR facility_id = current_setting(''app.current_facility_id'', true)::uuid
                 )',
                tbl
            );
        END IF;
    END LOOP;
END $$;
