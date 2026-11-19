/*
 * =============================================================================
 * Opes HealthCore SDK — PostgreSQL RLS Session Aspect Unit Tests (SEC-002)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.resilience;

import com.opeshealth.healthcore.persistence.resilience.ResilienceAndGovernanceSupport.PostgresRlsSessionAspect;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgresRlsSessionAspectTest {

    @Test
    void bindsFacilityIdAndClearsAfterExecution() throws Throwable {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        UUID facilityId = UUID.randomUUID();
        when(pjp.proceed()).thenReturn("ok");

        PostgresRlsSessionAspect aspect = new PostgresRlsSessionAspect(jdbc, () -> Optional.of(facilityId), true);
        Object result = aspect.bindPostgresFacilityTenant(pjp);

        assertEquals("ok", result);
        verify(jdbc).queryForObject("SELECT set_config('app.current_facility_id', ?, true)", String.class, facilityId.toString());
        verify(jdbc).queryForObject("SELECT set_config('app.current_facility_id', '', true)", String.class);
    }

    @Test
    void bindsEmptyStringFailClosedWhenNoFacilityContext() throws Throwable {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("empty");

        PostgresRlsSessionAspect aspect = new PostgresRlsSessionAspect(jdbc, Optional::empty, true);
        aspect.bindPostgresFacilityTenant(pjp);

        verify(jdbc).queryForObject("SELECT set_config('app.current_facility_id', ?, true)", String.class, "");
        verify(jdbc).queryForObject("SELECT set_config('app.current_facility_id', '', true)", String.class);
    }

    @Test
    void clearsGucEvenWhenTransactionalMethodThrows() throws Throwable {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        UUID facilityId = UUID.randomUUID();
        when(pjp.proceed()).thenThrow(new IllegalStateException("simulated failure"));

        PostgresRlsSessionAspect aspect = new PostgresRlsSessionAspect(jdbc, () -> Optional.of(facilityId), true);
        assertThrows(IllegalStateException.class, () -> aspect.bindPostgresFacilityTenant(pjp));

        verify(jdbc).queryForObject("SELECT set_config('app.current_facility_id', ?, true)", String.class, facilityId.toString());
        verify(jdbc).queryForObject("SELECT set_config('app.current_facility_id', '', true)", String.class);
    }

    @Test
    void skipsSetConfigOnNonPostgresDialects() throws Throwable {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("mssql");

        PostgresRlsSessionAspect aspect = new PostgresRlsSessionAspect(jdbc, () -> Optional.of(UUID.randomUUID()), false);
        assertEquals("mssql", aspect.bindPostgresFacilityTenant(pjp));
        verifyNoInteractions(jdbc);
    }
}
