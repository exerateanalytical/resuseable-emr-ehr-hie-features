/*
 * =============================================================================
 * Opes HealthCore SDK — Telemedicine & Low-Bandwidth Virtual Care Starter
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.ops.autoconfigure.HospitalOpsAutoConfiguration;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.TelemedicineSessionEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.TelemedicineRepository;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.springframework.security.access.AccessDeniedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * End-to-end Telemedicine &amp; Rural Virtual Care module providing WebRTC session
 * provisioning, real-time network telemetry evaluation, automatic 3G audio-only and
 * 2G asynchronous store-and-forward fallback adaptation, and PostgreSQL session persistence.
 */
@AutoConfiguration(after = HospitalOpsAutoConfiguration.class)
public class TelemedicineStarter {

    public record TelemedicineProvisionRequest(
            UUID facilityId,
            UUID patientId,
            String practitionerId,
            int initialBandwidthKbps
    ) {}

    public record NetworkTelemetryUpdate(
            int availableBandwidthKbps,
            double packetLossPercent,
            int rttLatencyMs
    ) {}

    public record AdaptiveBandwidthDecision(
            UUID sessionId,
            String roomToken,
            String previousMode,
            String adaptedTransportMode,
            boolean lowBandwidth3gFallbackActive,
            String targetCodecProfile,
            String clinicalAdvisory
    ) {}

    @Bean
    @ConditionalOnMissingBean
    public TelemedicineVirtualCareService telemedicineVirtualCareService(
            TelemedicineRepository telemedicineRepository) {
        return new TelemedicineVirtualCareService(telemedicineRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public TelemedicineVirtualCareController telemedicineVirtualCareController(
            TelemedicineVirtualCareService virtualCareService) {
        return new TelemedicineVirtualCareController(virtualCareService);
    }

    public static class TelemedicineVirtualCareService {

        private final TelemedicineRepository telemedicineRepository;

        public TelemedicineVirtualCareService(TelemedicineRepository telemedicineRepository) {
            this.telemedicineRepository = Objects.requireNonNull(telemedicineRepository, "TelemedicineRepository is required");
        }

        /**
         * Provisions a new PostgreSQL-persisted telemedicine consultation room with
         * deterministic cryptographic room token and initial bandwidth profile.
         */
        @Transactional
        @ClinicalAudit(action = "TELEMEDICINE_PROVISION_SESSION", resourceType = "TelemedicineSession")
        public TelemedicineSessionEntity provisionVirtualSession(TelemedicineProvisionRequest req) {
            Objects.requireNonNull(req, "TelemedicineProvisionRequest must not be null");
            if (req.patientId() == null || req.practitionerId() == null || req.practitionerId().isBlank()) {
                throw new IllegalArgumentException("patientId and practitionerId are required to provision a Telemedicine session");
            }

            UUID authorizedFacility = FacilityContext.requireFacilityId();
            if (req.facilityId() != null && !req.facilityId().equals(authorizedFacility)) {
                throw new AccessDeniedException("Cannot provision telemedicine session for another facility");
            }
            TelemedicineSessionEntity entity = new TelemedicineSessionEntity();
            entity.setFacilityId(authorizedFacility);
            entity.setPatientId(req.patientId());
            entity.setClinicianId(req.practitionerId().trim());

            int bw = req.initialBandwidthKbps() > 0 ? req.initialBandwidthKbps() : 512;
            boolean lowBw = bw < 250;
            entity.setLowBandwidthAudioMode(lowBw);
            entity.setStatus(lowBw ? "ACTIVE_3G_AUDIO_FALLBACK" : "ACTIVE_WEBRTC_VIDEO");
            entity.setSignalingRoomToken(generateSignedRoomToken(entity.getFacilityId(), req.patientId(), req.practitionerId()));

            return telemedicineRepository.save(entity);
        }

        /**
         * Evaluates live WebRTC network telemetry (kbps, packet loss %, RTT ms) and dynamically
         * switches the consultation transport between HD Video, SD Adaptive Video, 3G Opus Audio-Only,
         * and 2G Store-and-Forward mode so rural consultations never drop clinical context.
         */
        @Transactional
        @ClinicalAudit(action = "TELEMEDICINE_ADAPT_BANDWIDTH", resourceType = "TelemedicineSession")
        public AdaptiveBandwidthDecision evaluateAndAdaptBandwidth(UUID sessionId, NetworkTelemetryUpdate telemetry) {
            Objects.requireNonNull(telemetry, "NetworkTelemetryUpdate must not be null");
            TelemedicineSessionEntity session = telemedicineRepository.findByIdAndFacilityId(sessionId, FacilityContext.requireFacilityId())
                    .orElseThrow(() -> new NoSuchElementException("Telemedicine session not found: " + sessionId));

            String previousMode = session.getStatus();
            int kbps = Math.max(0, telemetry.availableBandwidthKbps());
            double loss = Math.max(0.0, telemetry.packetLossPercent());
            int rtt = Math.max(0, telemetry.rttLatencyMs());

            String newMode;
            String codecProfile;
            boolean lowBwFallback;
            String advisory;

            if (kbps < 48 || loss >= 10.0 || rtt >= 1200) {
                newMode = "ASYNC_STORE_AND_FORWARD_2G";
                codecProfile = "JSON+JPEG_COMPRESSED_STORE_FORWARD";
                lowBwFallback = true;
                advisory = "Severe link degradation (" + kbps + " kbps, " + loss + "% loss). Switched to asynchronous store-and-forward chart & voice-note synchronization.";
            } else if (kbps < 250 || loss >= 4.0 || rtt >= 550) {
                newMode = "ACTIVE_3G_AUDIO_FALLBACK";
                codecProfile = "OPUS_16KHZ_24KBPS_DTX_FEC";
                lowBwFallback = true;
                advisory = "Low-bandwidth 3G conditions detected (" + kbps + " kbps). Video muted automatically; prioritizing Opus FEC clinical voice channel.";
            } else if (kbps < 800) {
                newMode = "ACTIVE_SD_ADAPTIVE_VIDEO";
                codecProfile = "VP8_360P_15FPS_OPUS_AUDIO";
                lowBwFallback = false;
                advisory = "Moderate bandwidth (" + kbps + " kbps). Operating in 360p15 adaptive video mode.";
            } else {
                newMode = "ACTIVE_HD_WEBRTC_VIDEO";
                codecProfile = "VP9_720P_30FPS_OPUS_AUDIO";
                lowBwFallback = false;
                advisory = "Optimal broadband link (" + kbps + " kbps). Operating in 720p30 HD clinical video mode.";
            }

            session.setLowBandwidthAudioMode(lowBwFallback);
            session.setStatus(newMode);
            telemedicineRepository.save(session);

            return new AdaptiveBandwidthDecision(
                    session.getId(),
                    session.getSignalingRoomToken(),
                    previousMode,
                    newMode,
                    lowBwFallback,
                    codecProfile,
                    advisory
            );
        }

        private static String generateSignedRoomToken(UUID facilityId, UUID patientId, String practitionerId) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                String raw = facilityId + "|" + patientId + "|" + practitionerId + "|" + Instant.now().toEpochMilli() + "|" + UUID.randomUUID();
                String hex = HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
                return "OPES-RTC-" + hex.substring(0, 24).toUpperCase(Locale.ROOT);
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to generate telemedicine room token", ex);
            }
        }
    }

    @RestController
    @RequestMapping("/api/v1/telemedicine")
    @Tag(name = "Telemedicine & Rural 3G Virtual Care", description = "WebRTC consultation provisioning and real-time 3G/2G adaptive bandwidth fallback")
    public static class TelemedicineVirtualCareController {

        private final TelemedicineVirtualCareService virtualCareService;

        public TelemedicineVirtualCareController(TelemedicineVirtualCareService virtualCareService) {
            this.virtualCareService = virtualCareService;
        }

        @PostMapping("/sessions/provision")
        @PreAuthorize("hasAuthority('PERM_telemedicine:host') or hasAuthority('PERM_encounter:draft')")
        @Operation(summary = "Provision a new WebRTC / 3G-fallback Telemedicine consultation session")
        public TelemedicineSessionEntity provisionSession(@RequestBody TelemedicineProvisionRequest request) {
            return virtualCareService.provisionVirtualSession(request);
        }

        @PostMapping("/sessions/{sessionId}/adapt-bandwidth")
        @PreAuthorize("hasAuthority('PERM_telemedicine:host') or hasAuthority('PERM_patient:read')")
        @Operation(summary = "Adapt Telemedicine media transport based on live client bandwidth, packet loss, and RTT")
        public AdaptiveBandwidthDecision adaptBandwidth(
                @PathVariable UUID sessionId,
                @RequestBody NetworkTelemetryUpdate telemetry) {
            return virtualCareService.evaluateAndAdaptBandwidth(sessionId, telemetry);
        }
    }
}
