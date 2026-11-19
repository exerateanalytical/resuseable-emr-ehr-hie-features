/*
 * =============================================================================
 * Opes HealthCore SDK — Server-Side Java 25 Gemini Clinical API Client
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opeshealth.healthcore.ai.config.ClinicalAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Shared server-side Java 25 HTTP client for Google Gemini Developer API models used across
 * Opes HealthCore's Clinical &amp; Operational AI Suite.
 *
 * <p>Enforces active model routing ({@code gemini-4-hydrogen}, {@code gemini-3.5-transcribe},
 * {@code gemini-3.8-flash-lite-tts}, {@code gemini-embedding-2}, {@code gemini-decision-1}),
 * omits deprecated sampling parameters on Gemini 4 models, sends {@code User-Agent: aistudio-build},
 * applies exponential backoff for transient 429/503 responses, and propagates upstream errors cleanly
 * without fabricated fallback clinical outputs.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
public class GeminiClinicalApiClient {

    public static final String USER_AGENT_HEADER = "aistudio-build";

    private static final Logger log = LoggerFactory.getLogger(GeminiClinicalApiClient.class);

    private final ClinicalAiProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GeminiClinicalApiClient(ClinicalAiProperties properties) {
        this(properties, new ObjectMapper());
    }

    public GeminiClinicalApiClient(ClinicalAiProperties properties, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "ClinicalAiProperties is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper is required");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.properties.getConnectTimeoutSeconds()))
                .build();
    }

    public record InlineBinaryPart(String mimeType, String base64Data) {}

    public record TokenUsage(int promptTokens, int outputTokens, int totalTokens) {}

    public record TextGenerationResult(
            String modelId,
            String text,
            TokenUsage tokenUsage,
            long latencyMs
    ) {}

    public record AudioSynthesisResult(
            String modelId,
            String mimeType,
            byte[] wavAudioBytes,
            String base64Audio,
            long latencyMs
    ) {}

    public record EmbeddingResult(
            String modelId,
            List<Double> values,
            long latencyMs
    ) {}

    public record DecisionQuestion(
            String type,
            String instructions,
            Object criteria
    ) {}

    public record DecisionEvaluationResult(
            String modelId,
            Map<String, JsonNode> answers,
            JsonNode rawResponse,
            long latencyMs
    ) {}

    public record LiveTranscriptEvent(
            String text,
            boolean interim,
            boolean turnComplete
    ) {}

    /**
     * Handle for an active server-side Gemini Live API transcription session
     * ({@code gemini-3.5-transcribe-live}) streaming 16kHz 16-bit LE PCM audio chunks.
     */
    public interface LiveTranscriptionHandle extends AutoCloseable {
        String modelId();
        CompletableFuture<Void> sendPcm16kAudioChunk(String base64Pcm16kAudio);
        boolean isOpen();
        @Override
        void close();
    }

    /**
     * Generates structured JSON or clinical text using {@code gemini-4-hydrogen} with optional
     * multimodal inline binary parts (images, PDFs) and optional {@code thinkingLevel}.
     */
    public TextGenerationResult generateClinicalContent(
            String systemInstruction,
            String redactedUserPrompt,
            List<InlineBinaryPart> inlineParts,
            ObjectNode jsonResponseSchema,
            String thinkingLevel) {
        String model = properties.getTextModel();
        ObjectNode body = buildGenerateContentRequestBody(
                systemInstruction, redactedUserPrompt, inlineParts, jsonResponseSchema, thinkingLevel);
        long start = System.nanoTime();
        JsonNode response = executePostJson("/v1beta/models/" + model + ":generateContent", model, body);
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        String text = extractCandidateText(response, model);
        return new TextGenerationResult(model, text, extractTokenUsage(response), latencyMs);
    }

    /**
     * Transcribes pre-recorded clinical consultation audio using {@code gemini-3.5-transcribe}.
     */
    public TextGenerationResult transcribeClinicalAudio(
            String mimeType,
            String base64AudioData,
            String transcriptionPrompt) {
        if (base64AudioData == null || base64AudioData.isBlank()) {
            throw new GeminiApiException(400, "INVALID_AUDIO_PAYLOAD", properties.getTranscribeModel(),
                    "Base64 audio data is required for transcription.");
        }
        String model = properties.getTranscribeModel();
        String safeMime = (mimeType == null || mimeType.isBlank()) ? "audio/wav" : mimeType.trim();
        String prompt = (transcriptionPrompt == null || transcriptionPrompt.isBlank())
                ? "Transcribe this clinical consultation accurately with speaker labels (Clinician / Patient)."
                : transcriptionPrompt;
        ObjectNode body = buildTranscribeRequestBody(safeMime, base64AudioData, prompt);
        long start = System.nanoTime();
        JsonNode response = executePostJson("/v1beta/models/" + model + ":generateContent", model, body);
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        String text = extractCandidateText(response, model);
        return new TextGenerationResult(model, text, extractTokenUsage(response), latencyMs);
    }

    /**
     * Opens a real-time server-side Gemini Live API WebSocket session using
     * {@code gemini-3.5-transcribe-live} with {@code responseModalities: ["TEXT"]} and
     * {@code inputAudioTranscription}. Streams 16kHz 16-bit LE PCM audio chunks via
     * {@code realtimeInput.audio} and dispatches interim and finalized transcript events.
     */
    public LiveTranscriptionHandle openLiveTranscriptionSession(
            List<String> languageCodes,
            Consumer<LiveTranscriptEvent> onTranscriptEvent,
            Consumer<Throwable> onError) {
        String model = properties.getLiveTranscribeModel();
        if (!properties.isEnabled()) {
            throw new GeminiApiException(503, "CLINICAL_AI_DISABLED", model,
                    "Opes HealthCore Clinical AI subsystem is disabled by server configuration.");
        }
        String apiKey = properties.getApiKey();
        if (apiKey.isBlank()) {
            throw new GeminiApiException(503, "GEMINI_API_KEY_NOT_CONFIGURED", model,
                    "Server-side GEMINI_API_KEY is not configured. Configure GEMINI_API_KEY in server secrets before opening live transcription sessions.");
        }
        Objects.requireNonNull(onTranscriptEvent, "onTranscriptEvent callback is required");

        String wsBase = properties.getBaseUrl()
                .replaceFirst("^https://", "wss://")
                .replaceFirst("^http://", "ws://");
        URI wsUri = URI.create(wsBase
                + "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent");

        StringBuilder frameBuffer = new StringBuilder();
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                frameBuffer.append(data);
                if (last) {
                    String payload = frameBuffer.toString();
                    frameBuffer.setLength(0);
                    dispatchLiveServerMessage(payload, onTranscriptEvent, onError);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                if (onError != null) {
                    onError.accept(error);
                } else {
                    log.warn("Gemini live transcription WebSocket error on model={}: {}", model, error.getMessage());
                }
            }
        };

        try {
            WebSocket webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                    .header("x-goog-api-key", apiKey)
                    .header("User-Agent", USER_AGENT_HEADER)
                    .buildAsync(wsUri, listener)
                    .join();

            String setupJson = objectMapper.writeValueAsString(buildLiveTranscribeSetupMessage(languageCodes));
            webSocket.sendText(setupJson, true).join();

            return new LiveTranscriptionHandle() {
                @Override
                public String modelId() {
                    return model;
                }

                @Override
                public CompletableFuture<Void> sendPcm16kAudioChunk(String base64Pcm16kAudio) {
                    if (base64Pcm16kAudio == null || base64Pcm16kAudio.isBlank()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    try {
                        String json = objectMapper.writeValueAsString(
                                buildLiveRealtimeAudioInputMessage(base64Pcm16kAudio));
                        return webSocket.sendText(json, true).thenApply(ws -> null);
                    } catch (IOException ex) {
                        return CompletableFuture.failedFuture(new GeminiApiException(
                                500, "LIVE_AUDIO_SERIALIZATION_ERROR", model,
                                "Failed to serialize live PCM audio chunk.", ex));
                    }
                }

                @Override
                public boolean isOpen() {
                    return !webSocket.isInputClosed() && !webSocket.isOutputClosed();
                }

                @Override
                public void close() {
                    try {
                        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Encounter transcription finalized").join();
                    } catch (Exception ex) {
                        log.debug("Closing Gemini live transcription socket: {}", ex.getMessage());
                    }
                }
            };
        } catch (GeminiApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new GeminiApiException(502, "GEMINI_LIVE_CONNECT_FAILED", model,
                    "Failed to open Gemini Live transcription WebSocket session: " + ex.getMessage(), ex);
        }
    }

    /**
     * Generates patient-friendly unary WAV audio ({@code audio/wav}, 24kHz mono 16-bit) using
     * {@code gemini-3.8-flash-lite-tts}. Unary output already contains a complete RIFF WAV header.
     */
    public AudioSynthesisResult synthesizePatientSpeech(
            String textToSpeak,
            String voiceName,
            String speakingStyle) {
        if (textToSpeak == null || textToSpeak.isBlank()) {
            throw new GeminiApiException(400, "INVALID_TTS_TEXT", properties.getTtsModel(),
                    "Text content is required for patient speech synthesis.");
        }
        String model = properties.getTtsModel();
        String resolvedVoice = (voiceName == null || voiceName.isBlank())
                ? properties.getDefaultTtsVoice() : voiceName.trim();
        ObjectNode body = buildTtsRequestBody(textToSpeak, resolvedVoice, speakingStyle);
        long start = System.nanoTime();
        JsonNode response = executePostJson("/v1beta/models/" + model + ":generateContent", model, body);
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;

        JsonNode inlineData = response.path("candidates").path(0).path("content").path("parts").path(0).path("inlineData");
        String base64Audio = inlineData.path("data").asText("");
        if (base64Audio.isBlank()) {
            throw new GeminiApiException(502, "EMPTY_AUDIO_RESPONSE", model,
                    "Gemini TTS returned no inline audio data.");
        }
        String mimeType = inlineData.path("mimeType").asText("audio/wav");
        byte[] wavBytes = Base64.getDecoder().decode(base64Audio);
        return new AudioSynthesisResult(model, mimeType, wavBytes, base64Audio, latencyMs);
    }

    /**
     * Generates dense semantic embeddings for redacted clinical notes, lab summaries, or trial
     * protocols using {@code gemini-embedding-2}.
     */
    public EmbeddingResult embedClinicalText(String redactedText) {
        if (redactedText == null || redactedText.isBlank()) {
            throw new GeminiApiException(400, "INVALID_EMBEDDING_INPUT", properties.getEmbeddingModel(),
                    "Redacted clinical text is required for embedding generation.");
        }
        String model = properties.getEmbeddingModel();
        ObjectNode body = buildEmbedContentRequestBody(model, redactedText);
        long start = System.nanoTime();
        JsonNode response = executePostJson("/v1beta/models/" + model + ":embedContent", model, body);
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;

        JsonNode valuesNode = response.path("embedding").path("values");
        if (!valuesNode.isArray() || valuesNode.isEmpty()) {
            throw new GeminiApiException(502, "EMPTY_EMBEDDING_RESPONSE", model,
                    "Gemini embedding endpoint returned an empty vector.");
        }
        List<Double> vector = new ArrayList<>(valuesNode.size());
        for (JsonNode val : valuesNode) {
            vector.add(val.asDouble());
        }
        return new EmbeddingResult(model, List.copyOf(vector), latencyMs);
    }

    /**
     * Evaluates calibrated yes/no ({@code noul}), single-select ({@code choice}), and ordered rubric
     * ({@code score}) clinical triage or policy questions via {@code POST /v1beta/decisions} using
     * bare {@code model: "gemini-decision-1"} with zero generated output tokens.
     */
    public DecisionEvaluationResult evaluateClinicalDecisions(
            String redactedInputText,
            List<InlineBinaryPart> optionalMediaParts,
            Map<String, DecisionQuestion> questions) {
        if (redactedInputText == null || redactedInputText.isBlank()) {
            throw new GeminiApiException(400, "INVALID_DECISION_INPUT", properties.getDecisionsModel(),
                    "Clinical decision input text cannot be empty.");
        }
        if (questions == null || questions.isEmpty()) {
            throw new GeminiApiException(400, "INVALID_DECISION_QUESTIONS", properties.getDecisionsModel(),
                    "At least one calibrated decision question is required.");
        }
        String model = properties.getDecisionsModel();
        ObjectNode body = buildDecisionsRequestBody(model, redactedInputText, optionalMediaParts, questions);
        long start = System.nanoTime();
        JsonNode response = executePostJson("/v1beta/decisions", model, body);
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;

        JsonNode answersNode = response.path("answers");
        if (answersNode.isMissingNode() || !answersNode.isObject()) {
            String blockReason = response.path("prompt_feedback").path("block_reason").asText("NO_ANSWERS");
            throw new GeminiApiException(400, "DECISION_BLOCKED_OR_EMPTY", model,
                    "Decisions API omitted answers (reason: " + blockReason + ").");
        }
        Map<String, JsonNode> answers = new LinkedHashMap<>();
        answersNode.fields().forEachRemaining(e -> answers.put(e.getKey(), e.getValue()));
        return new DecisionEvaluationResult(model, answers, response, latencyMs);
    }

    // -------------------------------------------------------------------------
    // Public Wire-Payload Builders (Testable without live network calls)
    // -------------------------------------------------------------------------

    public ObjectNode buildGenerateContentRequestBody(
            String systemInstruction,
            String redactedUserPrompt,
            List<InlineBinaryPart> inlineParts,
            ObjectNode jsonResponseSchema,
            String thinkingLevel) {
        ObjectNode root = objectMapper.createObjectNode();

        if (systemInstruction != null && !systemInstruction.isBlank()) {
            ObjectNode sys = objectMapper.createObjectNode();
            ArrayNode sysParts = objectMapper.createArrayNode();
            sysParts.add(objectMapper.createObjectNode().put("text", systemInstruction));
            sys.set("parts", sysParts);
            root.set("systemInstruction", sys);
        }

        ArrayNode contents = objectMapper.createArrayNode();
        ObjectNode userTurn = objectMapper.createObjectNode();
        userTurn.put("role", "user");
        ArrayNode parts = objectMapper.createArrayNode();
        if (inlineParts != null) {
            for (InlineBinaryPart binary : inlineParts) {
                if (binary != null && binary.base64Data() != null && !binary.base64Data().isBlank()) {
                    ObjectNode part = objectMapper.createObjectNode();
                    ObjectNode inline = objectMapper.createObjectNode();
                    inline.put("mimeType", binary.mimeType());
                    inline.put("data", binary.base64Data());
                    part.set("inlineData", inline);
                    parts.add(part);
                }
            }
        }
        parts.add(objectMapper.createObjectNode().put("text", redactedUserPrompt));
        userTurn.set("parts", parts);
        contents.add(userTurn);
        root.set("contents", contents);

        ObjectNode config = objectMapper.createObjectNode();
        if (jsonResponseSchema != null) {
            config.put("responseMimeType", "application/json");
            config.set("responseSchema", jsonResponseSchema);
        }
        if (thinkingLevel != null && !thinkingLevel.isBlank()) {
            ObjectNode thinkingConfig = objectMapper.createObjectNode();
            thinkingConfig.put("thinkingLevel", thinkingLevel.trim().toUpperCase(java.util.Locale.ROOT));
            config.set("thinkingConfig", thinkingConfig);
        }
        if (!config.isEmpty()) {
            root.set("generationConfig", config);
        }
        return root;
    }

    public ObjectNode buildTranscribeRequestBody(String mimeType, String base64AudioData, String prompt) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        ObjectNode turn = objectMapper.createObjectNode();
        turn.put("role", "user");
        ArrayNode parts = objectMapper.createArrayNode();

        ObjectNode audioPart = objectMapper.createObjectNode();
        ObjectNode inlineData = objectMapper.createObjectNode();
        inlineData.put("mimeType", mimeType);
        inlineData.put("data", base64AudioData);
        audioPart.set("inlineData", inlineData);
        parts.add(audioPart);

        parts.add(objectMapper.createObjectNode().put("text", prompt));
        turn.set("parts", parts);
        contents.add(turn);
        root.set("contents", contents);
        return root;
    }

    /**
     * Builds the initial Live API {@code setup} message for {@code gemini-3.5-transcribe-live}
     * requesting {@code responseModalities: ["TEXT"]} and {@code inputAudioTranscription}.
     */
    public ObjectNode buildLiveTranscribeSetupMessage(List<String> languageCodes) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode setup = objectMapper.createObjectNode();
        setup.put("model", "models/" + properties.getLiveTranscribeModel());

        ObjectNode genConfig = objectMapper.createObjectNode();
        ArrayNode modalities = objectMapper.createArrayNode();
        modalities.add("TEXT");
        genConfig.set("responseModalities", modalities);
        setup.set("generationConfig", genConfig);

        ObjectNode inputAudioTranscription = objectMapper.createObjectNode();
        ArrayNode langs = objectMapper.createArrayNode();
        if (languageCodes != null) {
            for (String code : languageCodes) {
                if (code != null && !code.isBlank()) {
                    langs.add(code.trim());
                }
            }
        }
        inputAudioTranscription.set("languageCodes", langs);
        setup.set("inputAudioTranscription", inputAudioTranscription);

        root.set("setup", setup);
        return root;
    }

    /**
     * Builds a Live API {@code realtimeInput} frame streaming 16kHz 16-bit LE PCM audio via the
     * {@code audio} field (never deprecated {@code media} or {@code mediaChunks}).
     */
    public ObjectNode buildLiveRealtimeAudioInputMessage(String base64Pcm16kAudio) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode realtimeInput = objectMapper.createObjectNode();
        ObjectNode audio = objectMapper.createObjectNode();
        audio.put("mimeType", "audio/pcm;rate=16000");
        audio.put("data", base64Pcm16kAudio);
        realtimeInput.set("audio", audio);
        root.set("realtimeInput", realtimeInput);
        return root;
    }

    public ObjectNode buildTtsRequestBody(String textToSpeak, String voiceName, String speakingStyle) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        ObjectNode turn = objectMapper.createObjectNode();
        turn.put("role", "user");
        ArrayNode parts = objectMapper.createArrayNode();

        ObjectNode textPart = objectMapper.createObjectNode();
        textPart.put("text", textToSpeak);
        if (speakingStyle != null && !speakingStyle.isBlank()) {
            ObjectNode speechMetadata = objectMapper.createObjectNode();
            speechMetadata.put("style", speakingStyle.trim());
            textPart.set("speechMetadata", speechMetadata);
        }
        parts.add(textPart);
        turn.set("parts", parts);
        contents.add(turn);
        root.set("contents", contents);

        ObjectNode genConfig = objectMapper.createObjectNode();
        ArrayNode modalities = objectMapper.createArrayNode();
        modalities.add("AUDIO");
        genConfig.set("responseModalities", modalities);

        ObjectNode speechConfig = objectMapper.createObjectNode();
        ObjectNode voiceConfig = objectMapper.createObjectNode();
        ObjectNode prebuilt = objectMapper.createObjectNode();
        prebuilt.put("voiceName", voiceName);
        voiceConfig.set("prebuiltVoiceConfig", prebuilt);
        speechConfig.set("voiceConfig", voiceConfig);
        genConfig.set("speechConfig", speechConfig);

        root.set("generationConfig", genConfig);
        return root;
    }

    public ObjectNode buildEmbedContentRequestBody(String model, String redactedText) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", "models/" + model);
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode parts = objectMapper.createArrayNode();
        parts.add(objectMapper.createObjectNode().put("text", redactedText));
        content.set("parts", parts);
        root.set("content", content);
        return root;
    }

    public ObjectNode buildDecisionsRequestBody(
            String bareModelId,
            String redactedInputText,
            List<InlineBinaryPart> optionalMediaParts,
            Map<String, DecisionQuestion> questions) {
        ObjectNode root = objectMapper.createObjectNode();
        // Decisions API strictly requires bare model ID (without "models/" prefix) on /v1beta/decisions
        root.put("model", bareModelId.startsWith("models/") ? bareModelId.substring("models/".length()) : bareModelId);

        ArrayNode inputBlocks = objectMapper.createArrayNode();
        inputBlocks.add(objectMapper.createObjectNode()
                .put("type", "text")
                .put("text", redactedInputText));

        if (optionalMediaParts != null) {
            for (InlineBinaryPart part : optionalMediaParts) {
                if (part != null && part.base64Data() != null && !part.base64Data().isBlank()) {
                    ObjectNode mediaBlock = objectMapper.createObjectNode();
                    String blockType = part.mimeType() != null && part.mimeType().startsWith("application/pdf")
                            ? "document" : "image";
                    mediaBlock.put("type", blockType);
                    mediaBlock.put("data", part.base64Data());
                    mediaBlock.put("mime_type", part.mimeType());
                    if ("image".equals(blockType)) {
                        mediaBlock.put("resolution", "medium");
                    }
                    inputBlocks.add(mediaBlock);
                }
            }
        }
        root.set("input", inputBlocks);

        ObjectNode questionsNode = objectMapper.createObjectNode();
        for (Map.Entry<String, DecisionQuestion> entry : questions.entrySet()) {
            DecisionQuestion q = entry.getValue();
            ObjectNode qObj = objectMapper.createObjectNode();
            qObj.put("type", q.type());
            qObj.put("instructions", q.instructions());
            if (q.criteria() != null) {
                qObj.set("criteria", objectMapper.valueToTree(q.criteria()));
            }
            questionsNode.set(entry.getKey(), qObj);
        }
        root.set("questions", questionsNode);
        return root;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public ClinicalAiProperties getProperties() {
        return properties;
    }

    // -------------------------------------------------------------------------
    // Internal HTTP Execution with Exponential Backoff & Error Propagation
    // -------------------------------------------------------------------------

    private JsonNode executePostJson(String endpointPath, String modelId, ObjectNode requestBody) {
        if (!properties.isEnabled()) {
            throw new GeminiApiException(503, "CLINICAL_AI_DISABLED", modelId,
                    "Opes HealthCore Clinical AI subsystem is disabled by server configuration.");
        }
        String apiKey = properties.getApiKey();
        if (apiKey.isBlank()) {
            throw new GeminiApiException(503, "GEMINI_API_KEY_NOT_CONFIGURED", modelId,
                    "Server-side GEMINI_API_KEY is not configured. Configure GEMINI_API_KEY in server secrets before invoking clinical AI endpoints.");
        }

        URI uri = URI.create(properties.getBaseUrl() + endpointPath);
        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(requestBody);
        } catch (IOException ex) {
            throw new GeminiApiException(500, "SERIALIZATION_ERROR", modelId,
                    "Failed to serialize Gemini API request payload.", ex);
        }

        int maxAttempts = properties.getMaxRetries();
        long backoffMs = 400L;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                        .header("Content-Type", "application/json")
                        .header("x-goog-api-key", apiKey)
                        .header("User-Agent", USER_AGENT_HEADER)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return objectMapper.readTree(response.body());
                }

                boolean transientFailure = (status == 429 || status == 500 || status == 502 || status == 503 || status == 504);
                if (transientFailure && attempt < maxAttempts) {
                    log.warn("Transient Gemini API status={} on model={} attempt={}/{}; retrying after {}ms",
                            status, modelId, attempt, maxAttempts, backoffMs);
                    sleepQuietly(backoffMs);
                    backoffMs *= 2;
                    continue;
                }

                String upstreamMessage = extractUpstreamErrorMessage(response.body(), status);
                String errorCode = switch (status) {
                    case 400 -> "GEMINI_BAD_REQUEST_OR_SAFETY_BLOCK";
                    case 401, 403 -> "GEMINI_AUTH_OR_PERMISSION_DENIED";
                    case 404 -> "GEMINI_MODEL_OR_ENDPOINT_NOT_FOUND";
                    case 429 -> "GEMINI_QUOTA_EXHAUSTED";
                    default -> "GEMINI_UPSTREAM_ERROR";
                };
                throw new GeminiApiException(status, errorCode, modelId, upstreamMessage);
            } catch (GeminiApiException ex) {
                throw ex;
            } catch (IOException ex) {
                if (attempt < maxAttempts) {
                    log.warn("Transient network error calling Gemini model={} attempt={}/{}: {}",
                            modelId, attempt, maxAttempts, ex.getMessage());
                    sleepQuietly(backoffMs);
                    backoffMs *= 2;
                    continue;
                }
                throw new GeminiApiException(502, "GEMINI_NETWORK_FAILURE", modelId,
                        "Network error communicating with Gemini API: " + ex.getMessage(), ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new GeminiApiException(503, "GEMINI_REQUEST_INTERRUPTED", modelId,
                        "Gemini API request interrupted.", ex);
            }
        }
        throw new GeminiApiException(502, "GEMINI_RETRIES_EXHAUSTED", modelId,
                "Gemini API request failed after " + maxAttempts + " attempts.");
    }

    private String extractCandidateText(JsonNode response, String modelId) {
        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            String blockReason = response.path("promptFeedback").path("blockReason").asText("NO_CANDIDATES");
            throw new GeminiApiException(400, "GEMINI_CONTENT_BLOCKED", modelId,
                    "Gemini model returned no candidates (blockReason=" + blockReason + ").");
        }
        JsonNode parts = candidates.path(0).path("content").path("parts");
        StringBuilder sb = new StringBuilder();
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if (part.has("text")) {
                    sb.append(part.path("text").asText(""));
                }
            }
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) {
            throw new GeminiApiException(502, "GEMINI_EMPTY_TEXT", modelId,
                    "Gemini model returned an empty text candidate.");
        }
        return text;
    }

    private TokenUsage extractTokenUsage(JsonNode response) {
        JsonNode usage = response.path("usageMetadata");
        return new TokenUsage(
                usage.path("promptTokenCount").asInt(0),
                usage.path("candidatesTokenCount").asInt(0),
                usage.path("totalTokenCount").asInt(0)
        );
    }

    private String extractUpstreamErrorMessage(String rawBody, int status) {
        if (rawBody != null && !rawBody.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(rawBody);
                String msg = root.path("error").path("message").asText("");
                if (!msg.isBlank()) {
                    return "Gemini API HTTP " + status + ": " + msg;
                }
            } catch (IOException ignored) {
                // Fall back to bounded raw status message
            }
        }
        return "Gemini API returned HTTP " + status;
    }

    private void dispatchLiveServerMessage(
            String payload,
            Consumer<LiveTranscriptEvent> onTranscriptEvent,
            Consumer<Throwable> onError) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode serverContent = root.path("serverContent");
            if (serverContent.isMissingNode() || serverContent.isNull()) {
                return;
            }
            boolean turnComplete = serverContent.path("turnComplete").asBoolean(false);
            String interimText = serverContent.path("interimInputTranscription").path("text").asText("");
            if (!interimText.isBlank()) {
                onTranscriptEvent.accept(new LiveTranscriptEvent(interimText, true, turnComplete));
            }
            String finalText = serverContent.path("inputTranscription").path("text").asText("");
            if (!finalText.isBlank()) {
                onTranscriptEvent.accept(new LiveTranscriptEvent(finalText, false, turnComplete));
            }
        } catch (Exception ex) {
            if (onError != null) {
                onError.accept(ex);
            } else {
                log.warn("Failed to parse Gemini Live transcription frame: {}", ex.getMessage());
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
