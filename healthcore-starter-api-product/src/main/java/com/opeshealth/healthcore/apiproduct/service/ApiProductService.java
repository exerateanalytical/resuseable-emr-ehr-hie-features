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
import com.opeshealth.healthcore.security.crypto.PiiVaultCrypto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    }

    private final DeveloperAppRepository appRepo;

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

    /**
     * Sliding-window per-API-key rate limiter enforcing RPM tiers and returning
     * standard {@code X-RateLimit-Remaining} and {@code 429 Too Many Requests} responses.
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
            }
            filterChain.doFilter(request, response);
        }
    }

    @RestController
    @RequestMapping("/api/v1/developer")
    @Tag(name = "API-as-a-Product Portal", description = "Partner app registration, API keys, usage metering & HMAC webhooks")
    public static class ApiProductController {
        private final ApiProductService service;
        private final DeveloperAppRepository appRepo;

        public ApiProductController(ApiProductService service, DeveloperAppRepository appRepo) {
            this.service = service;
            this.appRepo = appRepo;
        }

        @PostMapping("/apps")
        @Operation(summary = "Provision sandbox or production API consumer app and key")
        public Map<String, Object> registerApp(
                @RequestParam String partnerName,
                @RequestParam(defaultValue = "SANDBOX") String planTier,
                @RequestParam(required = false) String webhookUrl,
                @RequestParam(defaultValue = "true") boolean sandbox) {
            return service.registerDeveloperApp(partnerName, planTier, webhookUrl, sandbox);
        }

        @GetMapping("/apps")
        @Operation(summary = "List registered developer portal apps & quota usage")
        public List<DeveloperAppEntity> listApps() {
            return appRepo.findAll();
        }
    }
}
