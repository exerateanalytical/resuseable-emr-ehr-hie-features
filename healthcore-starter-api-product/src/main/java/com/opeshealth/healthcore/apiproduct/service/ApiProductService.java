/*
 * =============================================================================
 * Opes HealthCore SDK — API-as-a-Product Portal, Rate Limiter & HMAC Webhooks
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.apiproduct.service;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.crypto.PiiVaultCrypto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ApiProductService {

    @Entity @Table(name = "hc_developer_apps")
    public static class DeveloperAppEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "partner_name", nullable = false, length = 160) private String partnerName;
        @Column(name = "plan_tier", nullable = false, length = 32) private String planTier = "SANDBOX";
        @Column(name = "api_key_hash", nullable = false, length = 88) private String apiKeyHash;
        @Column(name = "rate_limit_rpm", nullable = false) private int rateLimitRpm = 120;
        @Column(name = "monthly_quota", nullable = false) private long monthlyQuota = 100_000L;
        @Column(name = "current_month_calls", nullable = false) private long currentMonthCalls = 0L;
        @Column(name = "webhook_url", length = 400) private String webhookUrl;
        @Column(name = "webhook_secret", length = 128) private String webhookSecret;
        @Column(name = "sandbox_mode", nullable = false) private boolean sandboxMode = true;
        @Column(name = "active", nullable = false) private boolean active = true;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

        public UUID getId() { return id; }
        public String getPartnerName() { return partnerName; }
        public void setPartnerName(String partnerName) { this.partnerName = partnerName; }
        public String getPlanTier() { return planTier; }
        public void setPlanTier(String planTier) { this.planTier = planTier; }
        public String getApiKeyHash() { return apiKeyHash; }
        public void setApiKeyHash(String apiKeyHash) { this.apiKeyHash = apiKeyHash; }
        public int getRateLimitRpm() { return rateLimitRpm; }
        public void setRateLimitRpm(int rateLimitRpm) { this.rateLimitRpm = rateLimitRpm; }
        public long getMonthlyQuota() { return monthlyQuota; }
        public void setMonthlyQuota(long monthlyQuota) { this.monthlyQuota = monthlyQuota; }
        public long getCurrentMonthCalls() { return currentMonthCalls; }
        public void setCurrentMonthCalls(long currentMonthCalls) { this.currentMonthCalls = currentMonthCalls; }
        public String getWebhookUrl() { return webhookUrl; }
        public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
        public boolean isSandboxMode() { return sandboxMode; }
        public void setSandboxMode(boolean sandboxMode) { this.sandboxMode = sandboxMode; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public Instant getCreatedAt() { return createdAt; }
    }

    public interface DeveloperAppRepository extends JpaRepository<DeveloperAppEntity, UUID> {
        Optional<DeveloperAppEntity> findByApiKeyHashAndActiveTrue(String apiKeyHash);

        @Modifying
        @Transactional
        @Query("UPDATE DeveloperAppEntity d SET d.currentMonthCalls = d.currentMonthCalls + 1 WHERE d.id = :id AND d.active = true AND d.currentMonthCalls < d.monthlyQuota")
        int incrementMonthlyUsageIfWithinQuota(@Param("id") UUID id);
    }

    private final DeveloperAppRepository appRepo;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ApiProductService(DeveloperAppRepository appRepo) {
        this.appRepo = appRepo;
    }

    /** Registers a partner API consumer app and returns its one-time plaintext API key. */
    @Transactional
    public Map<String, Object> registerDeveloperApp(String partnerName, String planTier, String webhookUrl, boolean sandbox) {
        String rawApiKey = "opes_" + (sandbox ? "test_" : "live_") + UUID.randomUUID().toString().replace("-", "");
        DeveloperAppEntity app = new DeveloperAppEntity();
        app.setPartnerName(partnerName);
        app.setPlanTier(planTier);
        app.setApiKeyHash(PiiVaultCrypto.blindIndex(rawApiKey));
        app.setRateLimitRpm("ENTERPRISE".equalsIgnoreCase(planTier) ? 2000 : 120);
        app.setMonthlyQuota("ENTERPRISE".equalsIgnoreCase(planTier) ? 10_000_000L : 100_000L);
        app.setWebhookUrl(webhookUrl);
        app.setWebhookSecret("whsec_" + UUID.randomUUID().toString().replace("-", ""));
        app.setSandboxMode(sandbox);
        app = appRepo.save(app);

        return Map.of("appId", app.getId(), "apiKey", rawApiKey, "planTier", app.getPlanTier(),
                "rateLimitRpm", app.getRateLimitRpm(), "webhookSecret", app.getWebhookSecret());
    }

    /** Computes X-Opes-Signature-Sha256 header for outbound webhook deliveries. */
    public String signWebhookPayload(String payloadJson, String webhookSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Webhook signing failed", ex);
        }
    }

    /** Rotates an API consumer key immediately invalidating the previous credential. */
    @Transactional
    @ClinicalAudit(action = "API_KEY_ROTATE", resourceType = "DeveloperApp")
    public Map<String, Object> rotateApiKey(UUID appId) {
        DeveloperAppEntity app = appRepo.findById(appId)
                .orElseThrow(() -> new NoSuchElementException("Developer app not found: " + appId));
        String newKey = "opes_" + (app.isSandboxMode() ? "test_" : "live_") + UUID.randomUUID().toString().replace("-", "");
        app.setApiKeyHash(PiiVaultCrypto.blindIndex(newKey));
        appRepo.save(app);
        return Map.of("appId", app.getId(), "apiKey", newKey, "planTier", app.getPlanTier());
    }

    /** Revokes an API consumer app so all requests with its API key immediately return 401. */
    @Transactional
    @ClinicalAudit(action = "API_APP_REVOKE", resourceType = "DeveloperApp")
    public DeveloperAppEntity revokeApp(UUID appId) {
        DeveloperAppEntity app = appRepo.findById(appId)
                .orElseThrow(() -> new NoSuchElementException("Developer app not found: " + appId));
        app.setActive(false);
        return appRepo.save(app);
    }

    /** Dispatches an HMAC-SHA256 signed event webhook to a registered partner endpoint. */
    @CircuitBreaker(name = "hieWebhook", fallbackMethod = "webhookDeliveryFallback")
    public Map<String, Object> deliverSignedWebhook(UUID appId, String eventType, String payloadJson) {
        DeveloperAppEntity app = appRepo.findById(appId)
                .orElseThrow(() -> new NoSuchElementException("Developer app not found: " + appId));
        if (!app.isActive() || app.getWebhookUrl() == null || app.getWebhookUrl().isBlank()) {
            return Map.of("appId", appId, "delivered", false, "reason", "App inactive or webhookUrl unset");
        }
        String signature = signWebhookPayload(payloadJson, Objects.toString(app.getWebhookSecret(), "opes-webhook-secret"));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(app.getWebhookUrl()))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("X-Opes-Event-Type", eventType)
                    .header("X-Opes-Signature-Sha256", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return Map.of("appId", appId, "status", resp.statusCode(), "delivered", resp.statusCode() >= 200 && resp.statusCode() < 300);
        } catch (Exception ex) {
            throw new IllegalStateException("Developer webhook delivery failed: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> webhookDeliveryFallback(UUID appId, String eventType, String payloadJson, Throwable ex) {
        return Map.of("appId", appId, "eventType", eventType, "delivered", false, "reason", Objects.toString(ex.getMessage(), "unavailable"));
    }

    /**
     * Sliding-window per-API-key rate limiter enforcing RPM tiers and atomic PostgreSQL
     * monthly quota metering, returning {@code X-RateLimit-Remaining}, {@code X-Quota-Remaining},
     * and {@code 429 Too Many Requests} responses.
     */
    public static class ApiUsageMeteringFilter extends OncePerRequestFilter {
        private record MinuteWindow(long minuteEpoch, AtomicInteger counter) {}
        private final DeveloperAppRepository appRepo;
        private final ConcurrentHashMap<String, MinuteWindow> buckets = new ConcurrentHashMap<>();

        public ApiUsageMeteringFilter(DeveloperAppRepository appRepo) {
            this.appRepo = appRepo;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            String apiKey = request.getHeader("X-Opes-Api-Key");
            if (apiKey != null && !apiKey.isBlank()) {
                Optional<DeveloperAppEntity> opt = appRepo.findByApiKeyHashAndActiveTrue(PiiVaultCrypto.blindIndex(apiKey));
                if (opt.isEmpty()) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or inactive Opes API key");
                    return;
                }
                DeveloperAppEntity app = opt.get();
                long minute = Instant.now().getEpochSecond() / 60;
                MinuteWindow win = buckets.compute(app.getId().toString(), (k, existing) ->
                        (existing == null || existing.minuteEpoch() != minute)
                                ? new MinuteWindow(minute, new AtomicInteger(0))
                                : existing);
                int count = win.counter().incrementAndGet();
                response.setHeader("X-RateLimit-Limit", Integer.toString(app.getRateLimitRpm()));
                response.setHeader("X-RateLimit-Remaining", Integer.toString(Math.max(0, app.getRateLimitRpm() - count)));
                if (count > app.getRateLimitRpm()) {
                    response.setHeader("Retry-After", "60");
                    response.sendError(429, "Rate limit exceeded for plan tier " + app.getPlanTier());
                    return;
                }
                int quotaUpdated = appRepo.incrementMonthlyUsageIfWithinQuota(app.getId());
                if (quotaUpdated == 0) {
                    response.setHeader("X-Quota-Remaining", "0");
                    response.sendError(429, "Monthly API quota exceeded for plan tier " + app.getPlanTier());
                    return;
                }
                long remainingQuota = Math.max(0L, app.getMonthlyQuota() - (app.getCurrentMonthCalls() + 1));
                response.setHeader("X-Quota-Remaining", Long.toString(remainingQuota));
            }
            filterChain.doFilter(request, response);
        }
    }

    @RestController
    @RequestMapping("/api/v1/developer")
    @Tag(name = "API-as-a-Product Portal", description = "Partner app registration, API keys, rotation, usage metering & HMAC webhooks")
    public static class ApiProductController {
        private final ApiProductService service;
        private final DeveloperAppRepository appRepo;

        public ApiProductController(ApiProductService service, DeveloperAppRepository appRepo) {
            this.service = service;
            this.appRepo = appRepo;
        }

        @PostMapping("/apps")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Provision sandbox or production API consumer app and key")
        public Map<String, Object> registerApp(
                @RequestParam String partnerName,
                @RequestParam(defaultValue = "SANDBOX") String planTier,
                @RequestParam(required = false) String webhookUrl,
                @RequestParam(defaultValue = "true") boolean sandbox) {
            return service.registerDeveloperApp(partnerName, planTier, webhookUrl, sandbox);
        }

        @PostMapping("/apps/{appId}/rotate-key")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Rotate API key for a registered developer app")
        public Map<String, Object> rotateKey(@PathVariable UUID appId) {
            return service.rotateApiKey(appId);
        }

        @PostMapping("/apps/{appId}/revoke")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Revoke an API consumer app immediately")
        public DeveloperAppEntity revoke(@PathVariable UUID appId) {
            return service.revokeApp(appId);
        }

        @PostMapping("/apps/{appId}/webhook/test")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Send an HMAC-SHA256 signed webhook test event")
        public Map<String, Object> testWebhook(
                @PathVariable UUID appId,
                @RequestParam(defaultValue = "healthcore.ping") String eventType,
                @RequestBody(required = false) String payloadJson) {
            return service.deliverSignedWebhook(appId, eventType,
                    payloadJson != null && !payloadJson.isBlank() ? payloadJson : "{\"status\":\"ok\"}");
        }

        @GetMapping("/apps")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "List registered developer portal apps & quota usage")
        public List<DeveloperAppEntity> listApps() {
            return appRepo.findAll();
        }
    }
}
