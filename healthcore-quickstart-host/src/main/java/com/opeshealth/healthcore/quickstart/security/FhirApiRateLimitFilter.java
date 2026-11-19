/*
 * =============================================================================
 * Opes HealthCore SDK — Bucket4j FHIR API Token-Bucket Rate Limiting Filter
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.security;

import ca.uhn.fhir.context.FhirContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Bucket4j token-bucket Spring Security filter protecting FHIR R4 resource,
 * Bundle transaction/batch, MPI, terminology, and CDS Hooks API endpoints against
 * abuse and excessive burst traffic.
 *
 * <p>Buckets are scoped per rate tier, facility tenant, and authenticated principal
 * (or client IP for unauthenticated callers) inside a bounded Caffeine cache,
 * returning RFC 6585 {@code 429 Too Many Requests} with {@code Retry-After},
 * {@code X-RateLimit-*} headers, and an HL7 FHIR R4 {@link OperationOutcome} body.</p>
 */
public class FhirApiRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FhirApiRateLimitFilter.class);
    private static final FhirContext FHIR_R4 = FhirContext.forR4();
    private static final String FHIR_JSON_CONTENT_TYPE = "application/fhir+json;charset=UTF-8";

    public record RateLimitPolicy(
            boolean enabled,
            long fhirRequestsPerMinute,
            long bundleRequestsPerMinute,
            long anonymousRequestsPerMinute,
            Duration refillPeriod,
            long maxCachedBuckets
    ) {
        public RateLimitPolicy {
            if (fhirRequestsPerMinute <= 0) {
                throw new IllegalArgumentException("fhirRequestsPerMinute must be positive");
            }
            if (bundleRequestsPerMinute <= 0) {
                throw new IllegalArgumentException("bundleRequestsPerMinute must be positive");
            }
            if (anonymousRequestsPerMinute <= 0) {
                throw new IllegalArgumentException("anonymousRequestsPerMinute must be positive");
            }
            refillPeriod = refillPeriod != null && !refillPeriod.isNegative() && !refillPeriod.isZero()
                    ? refillPeriod
                    : Duration.ofMinutes(1);
            if (maxCachedBuckets <= 0) {
                maxCachedBuckets = 10_000L;
            }
        }

        public static RateLimitPolicy defaults() {
            return new RateLimitPolicy(true, 120L, 30L, 60L, Duration.ofMinutes(1), 10_000L);
        }
    }

    private enum LimitTier {
        BUNDLE,
        FHIR_AUTHENTICATED,
        FHIR_ANONYMOUS
    }

    private final RateLimitPolicy policy;
    private final Cache<String, Bucket> buckets;

    public FhirApiRateLimitFilter() {
        this(RateLimitPolicy.defaults());
    }

    public FhirApiRateLimitFilter(RateLimitPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "RateLimitPolicy is required");
        this.buckets = Caffeine.newBuilder()
                .maximumSize(policy.maxCachedBuckets())
                .expireAfterAccess(Duration.ofMinutes(15))
                .build();
    }

    public RateLimitPolicy getPolicy() {
        return policy;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!policy.enabled()) {
            return true;
        }
        String path = normalizePath(request);
        if (path.isEmpty()) {
            return true;
        }
        if (path.startsWith("/actuator/")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")) {
            return true;
        }
        return !isProtectedFhirPath(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = normalizePath(request);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = isAuthenticatedUser(auth);

        LimitTier tier = resolveTier(path, authenticated);
        long capacity = capacityForTier(tier);
        String callerKey = buildCallerKey(tier, request, auth, authenticated);

        Bucket bucket = buckets.get(callerKey, ignored -> createBucket(capacity, policy.refillPeriod()));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1L);

        response.setHeader("X-RateLimit-Limit", Long.toString(capacity));
        response.setHeader("X-RateLimit-Remaining", Long.toString(Math.max(0L, probe.getRemainingTokens())));

        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1L, (long) Math.ceil(probe.getNanosToWaitForRefill() / 1_000_000_000.0d));
        long resetEpochSeconds = Instant.now().getEpochSecond() + retryAfterSeconds;
        String correlationId = resolveCorrelationId(request);

        log.warn("FHIR rate limit exceeded tier={} callerKey={} path={} retryAfterSec={} correlationId={}",
                tier, callerKey, path, retryAfterSeconds, correlationId);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(FHIR_JSON_CONTENT_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setHeader("X-RateLimit-Reset", Long.toString(resetEpochSeconds));
        response.setHeader("X-Correlation-Id", correlationId);
        response.getWriter().write(buildThrottledOperationOutcomeJson(tier, path, retryAfterSeconds, correlationId));
    }

    private static Bucket createBucket(long capacity, Duration refillPeriod) {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, refillPeriod))
                .build();
    }

    private LimitTier resolveTier(String path, boolean authenticated) {
        if (isBundleEndpoint(path)) {
            return authenticated ? LimitTier.BUNDLE : LimitTier.FHIR_ANONYMOUS;
        }
        return authenticated ? LimitTier.FHIR_AUTHENTICATED : LimitTier.FHIR_ANONYMOUS;
    }

    private long capacityForTier(LimitTier tier) {
        return switch (tier) {
            case BUNDLE -> policy.bundleRequestsPerMinute();
            case FHIR_AUTHENTICATED -> policy.fhirRequestsPerMinute();
            case FHIR_ANONYMOUS -> policy.anonymousRequestsPerMinute();
        };
    }

    private static boolean isProtectedFhirPath(String path) {
        return path.equals("/fhir")
                || path.startsWith("/fhir/")
                || path.equals("/api/v1/fhir")
                || path.startsWith("/api/v1/fhir/")
                || path.equals("/api/v1/bundles")
                || path.startsWith("/api/v1/bundles/")
                || path.startsWith("/api/v1/patients")
                || path.startsWith("/api/v1/practitioners")
                || path.startsWith("/api/v1/encounters")
                || path.startsWith("/api/v1/observations")
                || path.startsWith("/api/v1/audit-events")
                || path.startsWith("/api/v1/cds-services")
                || path.startsWith("/cds-services")
                || path.startsWith("/api/v1/mpi")
                || path.startsWith("/api/v1/identity-matching")
                || path.startsWith("/api/v1/terminology")
                || path.startsWith("/api/v1/cold-chain")
                || path.startsWith("/api/v1/bulk-export")
                || path.startsWith("/api/v1/consent")
                || path.startsWith("/api/v1/pgx")
                || path.startsWith("/api/v1/rpm")
                || path.startsWith("/api/v1/subscriptions")
                || path.startsWith("/api/v1/bcma")
                || path.startsWith("/api/v1/ams")
                || path.startsWith("/api/v1/ips")
                || path.startsWith("/api/v1/prior-auth")
                || path.startsWith("/api/v1/proms")
                || path.startsWith("/api/v1/sdoh")
                || path.startsWith("/api/v1/certificates")
                || path.equals("/graphql")
                || path.startsWith("/graphql/");
    }

    private static boolean isBundleEndpoint(String path) {
        return path.equals("/api/v1/fhir")
                || path.equals("/api/v1/bundles")
                || path.equals("/fhir/r4")
                || path.startsWith("/api/v1/bulk-export")
                || path.contains("/$export");
    }

    private static String normalizePath(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri;
    }

    private static boolean isAuthenticatedUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        String name = auth.getName();
        return name != null && !name.isBlank() && !"anonymousUser".equalsIgnoreCase(name);
    }

    private static String buildCallerKey(
            LimitTier tier,
            HttpServletRequest request,
            Authentication auth,
            boolean authenticated) {
        String facilityKey = FacilityContext.getFacilityId()
                .map(UUID::toString)
                .orElseGet(() -> {
                    String hdr = request.getHeader("X-Facility-Id");
                    return (hdr != null && !hdr.isBlank()) ? hdr.trim() : "GLOBAL";
                });
        String principal = authenticated
                ? "user:" + auth.getName().trim()
                : "ip:" + resolveClientIp(request);
        return tier.name() + "|" + facilityKey + "|" + principal;
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma >= 0 ? xff.substring(0, comma) : xff).trim();
        }
        String remote = request.getRemoteAddr();
        return (remote != null && !remote.isBlank()) ? remote : "unknown";
    }

    private static String resolveCorrelationId(HttpServletRequest request) {
        String mdc = MDC.get("correlationId");
        if (mdc != null && !mdc.isBlank()) {
            return mdc;
        }
        String hdr = request.getHeader("X-Correlation-Id");
        return (hdr != null && !hdr.isBlank()) ? hdr.trim() : UUID.randomUUID().toString();
    }

    private static String buildThrottledOperationOutcomeJson(
            LimitTier tier,
            String path,
            long retryAfterSeconds,
            String correlationId) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.setId("oo-rate-limit-" + correlationId);
        OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue()
                .setSeverity(IssueSeverity.ERROR)
                .setCode(IssueType.THROTTLED)
                .setDiagnostics("FHIR API rate limit exceeded (" + tier.name() + ") for '" + path
                        + "'. Retry after " + retryAfterSeconds + " second(s).");
        issue.getDetails().addCoding()
                .setSystem("https://www.opeshealthsystems.com/fhir/CodeSystem/api-error")
                .setCode("RATE_LIMIT_EXCEEDED");
        return FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome);
    }
}
