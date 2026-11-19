/*
 * =============================================================================
 * Opes HealthCore SDK — Hibernate Multi-Tenant Filter Aspect & Idempotency Filter
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.resilience;

import jakarta.persistence.EntityManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Houses core cross-cutting resilience & multi-DB protections:
 * <ol>
 *   <li>{@link FacilityHibernateFilterAspect} — enables Hibernate {@code facilityFilter} on active sessions</li>
 *   <li>{@link HttpIdempotencyKeyFilter} — enforces {@code X-Idempotency-Key} replay protection using {@code hc_inbound_idempotency}</li>
 *   <li>{@link GlobalProblemDetailAdvice} — converts errors to RFC 7807 {@link ProblemDetail} or FHIR R4 {@code OperationOutcome}</li>
 * </ol>
 */
public final class ResilienceAndGovernanceSupport {

    private ResilienceAndGovernanceSupport() {}

    /** Functional provider bridging FacilityContext UUID without creating circular dependencies. */
    @FunctionalInterface
    public interface ActiveTenantIdSupplier {
        Optional<UUID> currentFacilityId();
    }

    /** Enables Hibernate {@code facilityFilter} whenever a tenant UUID is present. */
    @Aspect
    public static class FacilityHibernateFilterAspect {
        private final EntityManager entityManager;
        private final ActiveTenantIdSupplier tenantSupplier;

        public FacilityHibernateFilterAspect(EntityManager entityManager, ActiveTenantIdSupplier tenantSupplier) {
            this.entityManager = entityManager;
            this.tenantSupplier = tenantSupplier;
        }

        @Around("within(@org.springframework.stereotype.Service *) || within(@org.springframework.web.bind.annotation.RestController *)")
        public Object applyFacilityFilter(ProceedingJoinPoint pjp) throws Throwable {
            Optional<UUID> facilityId = tenantSupplier.currentFacilityId();
            if (facilityId.isPresent()) {
                Session session = entityManager.unwrap(Session.class);
                if (session.getSessionFactory().getDefinedFilterNames().contains("facilityFilter")) {
                    Filter filter = session.enableFilter("facilityFilter");
                    filter.setParameter("facilityId", facilityId.get());
                }
            }
            return pjp.proceed();
        }
    }

    /**
     * Defense-in-depth PostgreSQL Row-Level Security (RLS) session binder.
     *
     * <p>When {@code healthcore.postgres.rls.enabled=true} and PostgreSQL is the active
     * engine, binds the transaction-local GUC {@code app.current_facility_id} inside
     * {@code @Transactional} boundaries via {@code set_config(..., true)} so native JDBC
     * queries and reporting views are also isolated at the database kernel level.</p>
     */
    @Aspect
    public static class PostgresRlsSessionAspect {
        private final JdbcTemplate jdbcTemplate;
        private final ActiveTenantIdSupplier tenantSupplier;
        private final boolean postgresDialect;

        public PostgresRlsSessionAspect(
                JdbcTemplate jdbcTemplate,
                ActiveTenantIdSupplier tenantSupplier,
                boolean postgresDialect) {
            this.jdbcTemplate = jdbcTemplate;
            this.tenantSupplier = tenantSupplier;
            this.postgresDialect = postgresDialect;
        }

        @Around("@annotation(org.springframework.transaction.annotation.Transactional) || within(@org.springframework.transaction.annotation.Transactional *)")
        public Object bindPostgresFacilityTenant(ProceedingJoinPoint pjp) throws Throwable {
            if (!postgresDialect) {
                return pjp.proceed();
            }
            Optional<UUID> facilityId = tenantSupplier.currentFacilityId();
            String boundValue = facilityId.map(UUID::toString).orElse("");
            // Third arg 'true' scopes set_config strictly to the current PostgreSQL transaction.
            // Binding '' when absent enforces fail-closed RLS behavior on pooled connections.
            jdbcTemplate.queryForObject(
                    "SELECT set_config('app.current_facility_id', ?, true)",
                    String.class,
                    boundValue
            );
            try {
                return pjp.proceed();
            } finally {
                // Explicitly clear session GUC after execution so pooled connections never inherit prior tenant state.
                try {
                    jdbcTemplate.queryForObject(
                            "SELECT set_config('app.current_facility_id', '', true)",
                            String.class
                    );
                } catch (Exception ignored) {
                    // Transaction may already be marked rollback-only; PostgreSQL transaction-local GUC resets automatically on rollback.
                }
            }
        }
    }

    /**
     * Enforces {@code X-Idempotency-Key} deduplication on mutating HTTP methods ({@code POST}/{@code PUT}/{@code PATCH})
     * so flaky 3G/4G mobile retries never create duplicate clinical orders or billing charges.
     */
    public static class HttpIdempotencyKeyFilter extends OncePerRequestFilter {
        public static final String HEADER_IDEMPOTENCY_KEY = "X-Idempotency-Key";
        private final JdbcTemplate jdbcTemplate;

        public HttpIdempotencyKeyFilter(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {

            String key = request.getHeader(HEADER_IDEMPOTENCY_KEY);
            String method = request.getMethod();
            if (key == null || key.isBlank()
                    || (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method) && !"PATCH".equalsIgnoreCase(method))) {
                filterChain.doFilter(request, response);
                return;
            }

            String scopedKey = method + ":" + request.getRequestURI() + ":" + key.trim();
            try {
                Map<String, Object> existing = jdbcTemplate.queryForMap(
                        "SELECT http_status, response_body FROM hc_inbound_idempotency WHERE idempotency_key = ?",
                        scopedKey);
                int status = ((Number) existing.get("http_status")).intValue();
                String body = Objects.toString(existing.get("response_body"), "");
                response.setStatus(status);
                response.setHeader("X-Idempotency-Replayed", "true");
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(body);
                return;
            } catch (EmptyResultDataAccessException ignored) {
                // First execution for this idempotency key — proceed and cache result.
            }

            ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
            filterChain.doFilter(request, wrapped);

            int status = wrapped.getStatus();
            byte[] bodyBytes = wrapped.getContentAsByteArray();
            String bodyText = new String(bodyBytes, StandardCharsets.UTF_8);
            if (status >= 200 && status < 300) {
                jdbcTemplate.update(
                        "INSERT INTO hc_inbound_idempotency (id, idempotency_key, request_hash, http_status, response_body, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(), scopedKey, sha256Hex(scopedKey), status, bodyText, Timestamp.from(Instant.now()));
            }
            wrapped.copyBodyToResponse();
        }

        private String sha256Hex(String s) {
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception ex) {
                return Integer.toHexString(s.hashCode());
            }
        }
    }

    /**
     * Global exception advice returning HL7 FHIR R4 {@code OperationOutcome} for {@code /fhir/**} routes
     * and RFC 7807 {@link ProblemDetail} for all REST JSON routes.
     */
    @RestControllerAdvice
    public static class GlobalProblemDetailAdvice {

        @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
        public ResponseEntity<Object> handleBadRequest(Exception ex, HttpServletRequest req) {
            return formatError(HttpStatus.BAD_REQUEST, "invalid", ex.getMessage(), req);
        }

        @ExceptionHandler(NoSuchElementException.class)
        public ResponseEntity<Object> handleNotFound(NoSuchElementException ex, HttpServletRequest req) {
            return formatError(HttpStatus.NOT_FOUND, "not-found", ex.getMessage(), req);
        }

        @ExceptionHandler(SecurityException.class)
        public ResponseEntity<Object> handleForbidden(SecurityException ex, HttpServletRequest req) {
            return formatError(HttpStatus.FORBIDDEN, "security", ex.getMessage(), req);
        }

        @ExceptionHandler(IllegalStateException.class)
        public ResponseEntity<Object> handleConflict(IllegalStateException ex, HttpServletRequest req) {
            return formatError(HttpStatus.CONFLICT, "business-rule", ex.getMessage(), req);
        }

        private ResponseEntity<Object> formatError(HttpStatus status, String fhirCode, String detail, HttpServletRequest req) {
            if (req.getRequestURI().startsWith("/fhir/")) {
                Map<String, Object> outcome = Map.of(
                        "resourceType", "OperationOutcome",
                        "issue", List.of(Map.of(
                                "severity", "error",
                                "code", fhirCode,
                                "diagnostics", detail != null ? detail : status.getReasonPhrase()
                        ))
                );
                return ResponseEntity.status(status)
                        .contentType(MediaType.parseMediaType("application/fhir+json"))
                        .body(outcome);
            }
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail != null ? detail : status.getReasonPhrase());
            pd.setTitle(status.getReasonPhrase());
            pd.setProperty("module", "Opes HealthCore SDK");
            return ResponseEntity.status(status).body(pd);
        }
    }

    /**
     * Lightweight PostgreSQL transaction-scoped advisory lock helper ({@code pg_try_advisory_xact_lock})
     * preventing duplicate execution of scheduled tasks (IDSR reports, PITR snapshots, bulk exports)
     * across multi-pod deployments without external ZooKeeper/etcd infrastructure.
     */
    public static class PostgresAdvisoryLockHelper {
        private final JdbcTemplate jdbcTemplate;
        private final boolean postgresDialect;

        public PostgresAdvisoryLockHelper(JdbcTemplate jdbcTemplate, boolean postgresDialect) {
            this.jdbcTemplate = jdbcTemplate;
            this.postgresDialect = postgresDialect;
        }

        public boolean tryAcquireTransactionLock(String lockKey) {
            if (!postgresDialect) {
                return true;
            }
            Boolean acquired = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_xact_lock(hashtext(?))",
                    Boolean.class,
                    lockKey
            );
            return Boolean.TRUE.equals(acquired);
        }
    }
}
