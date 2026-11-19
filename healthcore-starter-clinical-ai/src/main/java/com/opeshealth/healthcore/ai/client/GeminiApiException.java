/*
 * =============================================================================
 * Opes HealthCore SDK — Clinical AI Gemini Upstream Exception
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.ai.client;

/**
 * Structured exception propagated whenever the server-side Gemini API key is missing,
 * an upstream Gemini API request fails (4xx/5xx), or a safety block occurs.
 *
 * <p>Clinical AI services must propagate this exception cleanly to API callers rather than
 * returning simulated or fabricated medical outputs.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
public class GeminiApiException extends RuntimeException {

    private final int httpStatus;
    private final String errorCode;
    private final String modelId;

    public GeminiApiException(int httpStatus, String errorCode, String modelId, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.modelId = modelId;
    }

    public GeminiApiException(int httpStatus, String errorCode, String modelId, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.modelId = modelId;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getModelId() {
        return modelId;
    }
}
