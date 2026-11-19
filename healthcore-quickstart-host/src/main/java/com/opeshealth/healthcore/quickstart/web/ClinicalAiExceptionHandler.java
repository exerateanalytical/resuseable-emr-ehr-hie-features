/*
 * =============================================================================
 * Opes HealthCore SDK — Clinical AI RFC 7807 Exception Handler
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.ai.client.GeminiApiException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates upstream {@link GeminiApiException} and AI tenant policy errors into clean,
 * structured RFC 7807 JSON responses preserving exact HTTP status codes without masking
 * missing-key or upstream Gemini API errors.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestControllerAdvice(assignableTypes = {
        AmbientClinicalScribeController.class,
        MedicalCodingAiController.class,
        ChartSummarizationAiController.class,
        MultimodalImageTriageController.class,
        ClinicalDecisionSupportAiController.class,
        RevenueCycleAiController.class,
        CalibratedClinicalDecisionController.class,
        PatientDischargeCoachAiController.class,
        SemanticClinicalSearchAiController.class,
        ClinicalAiGovernanceController.class
})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClinicalAiExceptionHandler {

    @ExceptionHandler(GeminiApiException.class)
    public ResponseEntity<Map<String, Object>> handleGeminiApiException(GeminiApiException ex) {
        int rawStatus = ex.getHttpStatus();
        HttpStatus resolved = HttpStatus.resolve(rawStatus);
        HttpStatus status = resolved != null ? resolved : HttpStatus.BAD_GATEWAY;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://opeshealthsystems.com/errors/clinical-ai/" + ex.getErrorCode().toLowerCase(java.util.Locale.ROOT));
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("errorCode", ex.getErrorCode());
        body.put("modelId", ex.getModelId());
        body.put("detail", ex.getMessage());
        body.put("occurredAt", Instant.now().toString());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleTenantPolicyException(IllegalStateException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://opeshealthsystems.com/errors/clinical-ai/tenant-policy-blocked");
        body.put("title", "Forbidden");
        body.put("status", 403);
        body.put("errorCode", "CLINICAL_AI_TENANT_POLICY_BLOCKED");
        body.put("detail", ex.getMessage());
        body.put("occurredAt", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
