/*
 * =============================================================================
 * Opes HealthCore SDK — Clinical AI Configuration Properties
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration properties for {@code healthcore-starter-clinical-ai}.
 *
 * <p>The Gemini API key is read strictly from server-side configuration or the
 * {@code GEMINI_API_KEY} environment variable and is never exposed to clients.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@ConfigurationProperties(prefix = "healthcore.ai")
public class ClinicalAiProperties {

    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
    public static final String DEFAULT_TEXT_MODEL = "gemini-4-hydrogen";
    public static final String DEFAULT_TRANSCRIBE_MODEL = "gemini-3.5-transcribe";
    public static final String DEFAULT_LIVE_TRANSCRIBE_MODEL = "gemini-3.5-transcribe-live";
    public static final String DEFAULT_TTS_MODEL = "gemini-3.8-flash-lite-tts";
    public static final String DEFAULT_EMBEDDING_MODEL = "gemini-embedding-2";
    public static final String DEFAULT_DECISIONS_MODEL = "gemini-decision-1";
    public static final String DEFAULT_TTS_VOICE = "Kore";

    private boolean enabled = true;
    private String apiKey = "";
    private String baseUrl = DEFAULT_BASE_URL;
    private String textModel = DEFAULT_TEXT_MODEL;
    private String transcribeModel = DEFAULT_TRANSCRIBE_MODEL;
    private String liveTranscribeModel = DEFAULT_LIVE_TRANSCRIBE_MODEL;
    private String ttsModel = DEFAULT_TTS_MODEL;
    private String embeddingModel = DEFAULT_EMBEDDING_MODEL;
    private String decisionsModel = DEFAULT_DECISIONS_MODEL;
    private String defaultTtsVoice = DEFAULT_TTS_VOICE;
    private boolean enforceSafeHarborRedaction = true;
    private boolean requireClinicianAttestation = true;
    private int connectTimeoutSeconds = 15;
    private int requestTimeoutSeconds = 60;
    private int maxRetries = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        String envKey = System.getenv("GEMINI_API_KEY");
        return envKey != null ? envKey.trim() : "";
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey != null ? apiKey : "";
    }

    public String getBaseUrl() {
        return baseUrl != null && !baseUrl.isBlank() ? baseUrl.replaceAll("/+$", "") : DEFAULT_BASE_URL;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getTextModel() {
        return textModel != null && !textModel.isBlank() ? textModel.trim() : DEFAULT_TEXT_MODEL;
    }

    public void setTextModel(String textModel) {
        this.textModel = textModel;
    }

    public String getTranscribeModel() {
        return transcribeModel != null && !transcribeModel.isBlank() ? transcribeModel.trim() : DEFAULT_TRANSCRIBE_MODEL;
    }

    public void setTranscribeModel(String transcribeModel) {
        this.transcribeModel = transcribeModel;
    }

    public String getLiveTranscribeModel() {
        return liveTranscribeModel != null && !liveTranscribeModel.isBlank()
                ? liveTranscribeModel.trim() : DEFAULT_LIVE_TRANSCRIBE_MODEL;
    }

    public void setLiveTranscribeModel(String liveTranscribeModel) {
        this.liveTranscribeModel = liveTranscribeModel;
    }

    public String getTtsModel() {
        return ttsModel != null && !ttsModel.isBlank() ? ttsModel.trim() : DEFAULT_TTS_MODEL;
    }

    public void setTtsModel(String ttsModel) {
        this.ttsModel = ttsModel;
    }

    public String getEmbeddingModel() {
        return embeddingModel != null && !embeddingModel.isBlank() ? embeddingModel.trim() : DEFAULT_EMBEDDING_MODEL;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public String getDecisionsModel() {
        return decisionsModel != null && !decisionsModel.isBlank() ? decisionsModel.trim() : DEFAULT_DECISIONS_MODEL;
    }

    public void setDecisionsModel(String decisionsModel) {
        this.decisionsModel = decisionsModel;
    }

    public String getDefaultTtsVoice() {
        return defaultTtsVoice != null && !defaultTtsVoice.isBlank() ? defaultTtsVoice.trim() : DEFAULT_TTS_VOICE;
    }

    public void setDefaultTtsVoice(String defaultTtsVoice) {
        this.defaultTtsVoice = defaultTtsVoice;
    }

    public boolean isEnforceSafeHarborRedaction() {
        return enforceSafeHarborRedaction;
    }

    public void setEnforceSafeHarborRedaction(boolean enforceSafeHarborRedaction) {
        this.enforceSafeHarborRedaction = enforceSafeHarborRedaction;
    }

    public boolean isRequireClinicianAttestation() {
        return requireClinicianAttestation;
    }

    public void setRequireClinicianAttestation(boolean requireClinicianAttestation) {
        this.requireClinicianAttestation = requireClinicianAttestation;
    }

    public int getConnectTimeoutSeconds() {
        return Math.max(3, connectTimeoutSeconds);
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getRequestTimeoutSeconds() {
        return Math.max(5, requestTimeoutSeconds);
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getMaxRetries() {
        return Math.max(1, Math.min(maxRetries, 5));
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
}
