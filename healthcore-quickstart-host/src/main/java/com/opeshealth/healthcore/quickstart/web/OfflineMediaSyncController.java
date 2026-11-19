/*
 * Copyright (c) 2026 Opes Health Systems (https://www.opeshealthsystems.com)
 *
 * Author: Jude Nshome — Opes Health Systems
 * Email: nshome@opesware.com
 * Organization: Opes Health Systems, Douala, Cameroon
 *
 * Licensed under the Opes HealthCore Enterprise SDK License.
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.OfflineMediaSyncService;
import com.opeshealth.healthcore.quickstart.service.OfflineMediaSyncService.InitMediaUploadRequest;
import com.opeshealth.healthcore.quickstart.service.OfflineMediaSyncService.MediaChunkUploadRequest;
import com.opeshealth.healthcore.quickstart.service.OfflineMediaSyncService.MediaUploadSessionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller for Resumable, Chunked, SHA-256-Verified Encrypted Offline Media Sync
 * paired with KMP {@code DataSyncManager}.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/sync/media")
@Tag(name = "Offline-First Encrypted Media & Binary Attachment Sync",
        description = "Resumable, chunked, SHA-256-verified encrypted binary uploads (wound photos, PDFs, ultrasound clips) for KMP mobile devices")
public class OfflineMediaSyncController {

    private final OfflineMediaSyncService mediaSyncService;

    public OfflineMediaSyncController(OfflineMediaSyncService mediaSyncService) {
        this.mediaSyncService = Objects.requireNonNull(mediaSyncService, "OfflineMediaSyncService is required");
    }

    @PostMapping(value = "/init", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Initialize a resumable chunked encrypted media upload session")
    public ResponseEntity<MediaUploadSessionStatus> initiateUpload(
            @RequestBody InitMediaUploadRequest request) {
        return ResponseEntity.status(201).body(mediaSyncService.initiateUpload(request));
    }

    @PostMapping(value = "/{uploadId}/chunks", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Upload an individual Base64-encoded encrypted media chunk with optional SHA-256 check")
    public ResponseEntity<MediaUploadSessionStatus> uploadChunk(
            @PathVariable("uploadId") UUID uploadId,
            @RequestBody MediaChunkUploadRequest chunkRequest) {
        return ResponseEntity.ok(mediaSyncService.uploadChunk(uploadId, chunkRequest));
    }

    @PostMapping(value = "/{uploadId}/complete", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Reassemble all chunks, verify full-file SHA-256 checksum, and finalize FHIR DocumentReference")
    public ResponseEntity<MediaUploadSessionStatus> completeUpload(@PathVariable("uploadId") UUID uploadId) {
        return ResponseEntity.ok(mediaSyncService.completeUpload(uploadId));
    }

    @GetMapping(value = "/{uploadId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Check resumable upload progress and missing chunk indexes")
    public ResponseEntity<MediaUploadSessionStatus> getUploadStatus(@PathVariable("uploadId") UUID uploadId) {
        return ResponseEntity.ok(mediaSyncService.getUploadStatus(uploadId));
    }
}
