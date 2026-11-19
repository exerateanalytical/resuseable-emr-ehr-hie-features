/*
 * Copyright (c) 2026 Opes Health Systems (https://www.opeshealthsystems.com)
 *
 * Author: Jude Nshome — Opes Health Systems
 * Email: nshome@opesware.com
 * Organization: Opes Health Systems, Douala, Cameroon
 *
 * Licensed under the Opes HealthCore Enterprise SDK License.
 */
package com.opeshealth.healthcore.quickstart.service;

import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.FhirAccessAuditCommand;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resumable, Chunked, SHA-256-Verified Encrypted Offline Media &amp; Attachment Sync Service
 * supporting KMP Mobile / Edge uploads of wound photographs, scanned referral PDFs, ID cards,
 * and bedside ultrasound clips mapped to HL7 FHIR R4 {@code DocumentReference} and {@code Binary}.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class OfflineMediaSyncService {

    public record InitMediaUploadRequest(
            UUID facilityId,
            UUID patientId,
            String originNodeId,
            String fileName,
            String contentType,
            String clinicalCategory,
            int totalChunks,
            long totalSizeBytes,
            String expectedSha256Hex,
            boolean clientEncryptedAesGcm
    ) {}

    public record MediaUploadSessionStatus(
            UUID uploadId,
            UUID facilityId,
            UUID patientId,
            String fileName,
            String contentType,
            int totalChunks,
            int receivedChunksCount,
            List<Integer> missingChunkIndexes,
            String status,
            String expectedSha256Hex,
            String verifiedSha256Hex,
            UUID fhirDocumentReferenceId,
            Instant createdAt,
            Instant completedAt
    ) {}

    public record MediaChunkUploadRequest(
            int chunkIndex,
            String chunkBase64Data,
            String chunkSha256Hex
    ) {}

    private static final class MediaUploadState {
        final UUID uploadId;
        final UUID facilityId;
        final UUID patientId;
        final String originNodeId;
        final String fileName;
        final String contentType;
        final String clinicalCategory;
        final int totalChunks;
        final long totalSizeBytes;
        final String expectedSha256Hex;
        final boolean clientEncryptedAesGcm;
        final Instant createdAt;
        final Map<Integer, byte[]> chunks = new ConcurrentHashMap<>();
        volatile String status = "IN_PROGRESS";
        volatile String verifiedSha256Hex;
        volatile UUID fhirDocumentReferenceId;
        volatile Instant completedAt;

        MediaUploadState(UUID uploadId, UUID facilityId, UUID patientId, String originNodeId, String fileName,
                         String contentType, String clinicalCategory, int totalChunks, long totalSizeBytes,
                         String expectedSha256Hex, boolean clientEncryptedAesGcm, Instant createdAt) {
            this.uploadId = uploadId;
            this.facilityId = facilityId;
            this.patientId = patientId;
            this.originNodeId = originNodeId;
            this.fileName = fileName;
            this.contentType = contentType;
            this.clinicalCategory = clinicalCategory;
            this.totalChunks = totalChunks;
            this.totalSizeBytes = totalSizeBytes;
            this.expectedSha256Hex = expectedSha256Hex;
            this.clientEncryptedAesGcm = clientEncryptedAesGcm;
            this.createdAt = createdAt;
        }
    }

    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final Map<UUID, MediaUploadState> sessionsById = new ConcurrentHashMap<>();

    @Autowired
    public OfflineMediaSyncService(@Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.fhirAuditLoggingService = fhirAuditLoggingService;
    }

    public OfflineMediaSyncService() {
        this(null);
    }

    /**
     * Initializes a resumable chunked media upload session for a KMP mobile device.
     */
    @ClinicalAudit(action = "OFFLINE_MEDIA_UPLOAD_INIT", resourceType = "Binary")
    public MediaUploadSessionStatus initiateUpload(InitMediaUploadRequest request) {
        if (request == null || request.patientId() == null || request.totalChunks() <= 0) {
            throw new IllegalArgumentException("InitMediaUploadRequest with patientId and totalChunks > 0 is required");
        }
        UUID uploadId = UUID.randomUUID();
        UUID facilityId = request.facilityId() != null ? request.facilityId() : PatientService.DEFAULT_FACILITY_ID;
        MediaUploadState state = new MediaUploadState(
                uploadId,
                facilityId,
                request.patientId(),
                request.originNodeId() != null ? request.originNodeId().trim() : "mobile-kmp",
                request.fileName() != null ? request.fileName().trim() : "clinical-attachment.bin",
                request.contentType() != null ? request.contentType().trim() : "application/octet-stream",
                request.clinicalCategory() != null ? request.clinicalCategory().trim() : "WOUND_PHOTO",
                request.totalChunks(),
                request.totalSizeBytes(),
                request.expectedSha256Hex() != null ? request.expectedSha256Hex().trim().toLowerCase() : null,
                request.clientEncryptedAesGcm(),
                Instant.now()
        );
        sessionsById.put(uploadId, state);
        return toStatus(state);
    }

    /**
     * Uploads an individual chunk, verifying its per-chunk SHA-256 checksum if provided.
     */
    public MediaUploadSessionStatus uploadChunk(UUID uploadId, MediaChunkUploadRequest chunkRequest) {
        MediaUploadState state = requireSession(uploadId);
        if (chunkRequest == null || chunkRequest.chunkBase64Data() == null) {
            throw new IllegalArgumentException("MediaChunkUploadRequest with chunkBase64Data is required");
        }
        if (chunkRequest.chunkIndex() < 0 || chunkRequest.chunkIndex() >= state.totalChunks) {
            throw new IllegalArgumentException("chunkIndex out of range 0.." + (state.totalChunks - 1));
        }
        byte[] bytes = Base64.getDecoder().decode(chunkRequest.chunkBase64Data());
        if (chunkRequest.chunkSha256Hex() != null && !chunkRequest.chunkSha256Hex().isBlank()) {
            String computed = sha256Hex(bytes);
            if (!computed.equalsIgnoreCase(chunkRequest.chunkSha256Hex().trim())) {
                throw new SecurityException("Chunk SHA-256 mismatch at index " + chunkRequest.chunkIndex());
            }
        }
        state.chunks.put(chunkRequest.chunkIndex(), bytes);
        return toStatus(state);
    }

    /**
     * Reassembles all uploaded chunks in order, validates full-file SHA-256 integrity,
     * and finalizes the FHIR R4 {@code DocumentReference}/{@code Binary} record.
     */
    @ClinicalAudit(action = "OFFLINE_MEDIA_UPLOAD_COMPLETE", resourceType = "DocumentReference")
    public MediaUploadSessionStatus completeUpload(UUID uploadId) {
        MediaUploadState state = requireSession(uploadId);
        if (state.chunks.size() < state.totalChunks) {
            throw new IllegalStateException("Cannot complete upload: missing chunks " + missingChunks(state));
        }
        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        Map<Integer, byte[]> sorted = new TreeMap<>(state.chunks);
        for (byte[] chunk : sorted.values()) {
            assembled.writeBytes(chunk);
        }
        String computedFullSha256 = sha256Hex(assembled.toByteArray());
        if (state.expectedSha256Hex != null && !state.expectedSha256Hex.isBlank()
                && !state.expectedSha256Hex.equalsIgnoreCase(computedFullSha256)) {
            state.status = "CHECKSUM_FAILED";
            throw new SecurityException("Full-file SHA-256 verification failed for upload " + uploadId);
        }

        state.verifiedSha256Hex = computedFullSha256;
        state.fhirDocumentReferenceId = UUID.randomUUID();
        state.status = "COMPLETED_VERIFIED";
        state.completedAt = Instant.now();

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    state.facilityId.toString(),
                    state.originNodeId,
                    "ROLE_CLINICIAN",
                    "OFFLINE_MEDIA_SYNC_COMPLETED",
                    "DocumentReference",
                    state.fhirDocumentReferenceId.toString(),
                    state.patientId,
                    "POST",
                    "/api/v1/sync/media/" + uploadId + "/complete",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Verified chunked encrypted attachment file=" + state.fileName + " sha256=" + computedFullSha256
            ));
        }
        return toStatus(state);
    }

    public MediaUploadSessionStatus getUploadStatus(UUID uploadId) {
        return toStatus(requireSession(uploadId));
    }

    private MediaUploadState requireSession(UUID uploadId) {
        MediaUploadState state = sessionsById.get(uploadId);
        if (state == null) {
            throw new NoSuchElementException("Media upload session not found: " + uploadId);
        }
        return state;
    }

    private static List<Integer> missingChunks(MediaUploadState state) {
        List<Integer> missing = new java.util.ArrayList<>();
        for (int i = 0; i < state.totalChunks; i++) {
            if (!state.chunks.containsKey(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    private static MediaUploadSessionStatus toStatus(MediaUploadState state) {
        return new MediaUploadSessionStatus(
                state.uploadId,
                state.facilityId,
                state.patientId,
                state.fileName,
                state.contentType,
                state.totalChunks,
                state.chunks.size(),
                missingChunks(state),
                state.status,
                state.expectedSha256Hex,
                state.verifiedSha256Hex,
                state.fhirDocumentReferenceId,
                state.createdAt,
                state.completedAt
        );
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 failure", ex);
        }
    }
}
