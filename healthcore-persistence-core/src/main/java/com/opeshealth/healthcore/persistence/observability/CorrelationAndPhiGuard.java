/*
 * =============================================================================
 * Opes HealthCore SDK — PHI-Masking Log Scrubbing & Correlation ID Filter
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.observability;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Servlet filter and PHI scrubbing utilities guaranteeing every HTTP request carries a
 * traceable {@code X-Correlation-Id} in SLF4J MDC while redacting sensitive patient data
 * before log emission.
 */
public final class CorrelationAndPhiGuard implements Filter {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern PHONE_PATTERN = Pattern.compile("(\\+?\\d{1,3}[\\s-]?)?\\d{3}[\\s-]?\\d{3}[\\s-]?\\d{3,4}");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern NATIONAL_ID_PATTERN = Pattern.compile("(?i)(cni|nin|ssn|passport)[\"':=\\s]+([A-Z0-9-]{6,20})");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String cid = req.getHeader(CORRELATION_HEADER);
        if (cid == null || cid.isBlank()) {
            cid = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, cid);
        res.setHeader(CORRELATION_HEADER, cid);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Masks phone numbers, emails, and national IDs from exception diagnostics before logging. */
    public static String redactPhi(String raw) {
        if (raw == null) return null;
        String masked = EMAIL_PATTERN.matcher(raw).replaceAll("[REDACTED_EMAIL]");
        masked = NATIONAL_ID_PATTERN.matcher(masked).replaceAll("$1=[REDACTED_ID]");
        return PHONE_PATTERN.matcher(masked).replaceAll("[REDACTED_PHONE]");
    }
}
