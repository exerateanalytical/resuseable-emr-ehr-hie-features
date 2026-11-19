/*
 * =============================================================================
 * Opes HealthCore SDK — Binary DICOMweb PACS Proxy (STOW-RS / WADO-RS / QIDO-RS)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.hie.service;

import com.opeshealth.healthcore.hie.domain.HieEntities.DicomStudyEntity;
import com.opeshealth.healthcore.hie.domain.HieEntities.HieDlqMessageEntity;
import com.opeshealth.healthcore.hie.service.HieInteropService.DicomStudyRepository;
import com.opeshealth.healthcore.hie.service.HieInteropService.HieDlqRepository;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * DICOMweb binary proxy bridging Opes HealthCore into external PACS servers
 * (Orthanc, DCM4CHEE Arc, or cloud VNA) across QIDO-RS, WADO-RS, and STOW-RS.
 *
 * <p>When {@code healthcore.hie.dicom.pacs-enabled=false} (default local/offline mode),
 * study metadata still indexes atomically into {@code hc_dicom_studies} while binary
 * upstream forwarding returns a clear metadata receipt without external network failures.</p>
 */
public class DicomPacsProxyService {

    private final DicomStudyRepository dicomRepo;
    private final HieDlqRepository dlqRepo;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    @Value("${healthcore.hie.dicom.pacs-enabled:false}")
    private boolean pacsEnabled;

    @Value("${healthcore.hie.dicom.pacs-base-url:}")
    private String pacsBaseUrl;

    @Value("${healthcore.hie.dicom.pacs-auth-header:}")
    private String pacsAuthHeader;

    public DicomPacsProxyService(DicomStudyRepository dicomRepo, HieDlqRepository dlqRepo) {
        this.dicomRepo = dicomRepo;
        this.dlqRepo = dlqRepo;
    }

    private static final java.util.regex.Pattern DICOM_UID_PATTERN =
            java.util.regex.Pattern.compile("^(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*))*$");

    public static void validateDicomUid(String uid, String fieldName) {
        if (uid == null || uid.isBlank() || uid.length() > 64 || !DICOM_UID_PATTERN.matcher(uid).matches()) {
            throw new IllegalArgumentException("Invalid DICOM UID for " + fieldName + ": '" + uid
                    + "'. Must contain numeric dot-separated components without leading zeros (max 64 chars).");
        }
    }

    /** Indexes study metadata locally and forwards binary DICOM frame via STOW-RS when configured. */
    @Transactional
    @ClinicalAudit(action = "DICOM_STOW_RS_PROXY", resourceType = "DicomStudy")
    @CircuitBreaker(name = "pacsProxy", fallbackMethod = "stowFallback")
    @Bulkhead(name = "hieOutbound", fallbackMethod = "stowFallback")
    public Map<String, Object> storeBinaryStudy(
            UUID facilityId,
            UUID patientId,
            String studyInstanceUid,
            String modality,
            String studyDescription,
            String contentType,
            byte[] dicomBytes) {
        UUID activeFacility = HieInteropService.requireTenantFacilityId();
        if (facilityId != null && !facilityId.equals(activeFacility)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Cross-tenant DICOM STOW-RS denied: facility header does not match authenticated tenant.");
        }
        validateDicomUid(studyInstanceUid, "StudyInstanceUID");
        if (patientId == null) {
            throw new IllegalArgumentException("patientId is required for DICOM STOW-RS.");
        }
        if (dicomBytes == null || dicomBytes.length == 0) {
            throw new IllegalArgumentException("Non-empty binary DICOM payload is required for STOW-RS.");
        }
        if (!pacsEnabled || pacsBaseUrl == null || pacsBaseUrl.isBlank()) {
            throw new UnsupportedOperationException(
                    "External PACS DICOMweb storage is disabled (healthcore.hie.dicom.pacs-enabled=false). "
                            + "Binary DICOM instances cannot be stored without a configured PACS server; "
                            + "use POST /api/v1/hie/dicom/studies for metadata-only indexing.");
        }

        DicomStudyEntity entity = dicomRepo.findFirstByFacilityIdAndStudyInstanceUid(activeFacility, studyInstanceUid)
                .orElseGet(DicomStudyEntity::new);
        entity.setFacilityId(activeFacility);
        entity.setPatientId(patientId);
        entity.setStudyInstanceUid(studyInstanceUid);
        entity.setModality(modality != null && !modality.isBlank() ? modality : "DX");
        entity.setStudyDescription(studyDescription != null ? studyDescription : "DICOMweb STOW-RS Study");
        String resolvedWado = buildWadoStudyUrl(studyInstanceUid);
        entity.setWadoRsUrl(resolvedWado);
        DicomStudyEntity saved = dicomRepo.save(entity);

        try {
            String target = normalizedBase() + "/studies";
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", contentType != null && !contentType.isBlank()
                            ? contentType
                            : "application/dicom");
            applyAuth(req);
            HttpResponse<String> resp = httpClient.send(
                    req.POST(HttpRequest.BodyPublishers.ofByteArray(dicomBytes)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("PACS STOW-RS HTTP " + resp.statusCode());
            }
            return Map.of(
                    "status", "FORWARDED_TO_PACS",
                    "pacsHttpStatus", resp.statusCode(),
                    "studyId", saved.getId(),
                    "studyInstanceUid", saved.getStudyInstanceUid(),
                    "bytesStored", dicomBytes.length,
                    "wadoRsUrl", resolvedWado
            );
        } catch (Exception ex) {
            throw new IllegalStateException("DICOMweb STOW-RS proxy error: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> stowFallback(
            UUID facilityId, UUID patientId, String studyInstanceUid, String modality,
            String studyDescription, String contentType, byte[] dicomBytes, Throwable ex) {
        // Never swallow validation, authorization, or unsupported-configuration errors into a fake DLQ receipt.
        if (ex instanceof org.springframework.security.access.AccessDeniedException ade) {
            throw ade;
        }
        if (ex instanceof IllegalArgumentException iae) {
            throw iae;
        }
        if (ex instanceof UnsupportedOperationException uoe) {
            throw uoe;
        }
        UUID activeFacility = HieInteropService.requireTenantFacilityId();
        HieDlqMessageEntity dlq = new HieDlqMessageEntity();
        dlq.setProtocol("DICOM");
        dlq.setExternalMessageId(studyInstanceUid);
        dlq.setRawPayload("{\"facilityId\":\"" + activeFacility + "\",\"patientId\":\"" + patientId
                + "\",\"bytes\":" + (dicomBytes != null ? dicomBytes.length : 0) + "}");
        dlq.setErrorDetail(ex.getMessage());
        dlqRepo.save(dlq);
        return Map.of(
                "status", "QUARANTINED_TO_DLQ",
                "facilityId", activeFacility,
                "studyInstanceUid", studyInstanceUid,
                "reason", Objects.toString(ex.getMessage(), "PACS unavailable")
        );
    }

    /** Retrieves binary DICOM study or instance bytes via WADO-RS from upstream PACS. */
    @CircuitBreaker(name = "pacsProxy")
    @Bulkhead(name = "hieOutbound")
    public ResponseEntity<byte[]> retrieveWadoBinary(String studyUid, String seriesUid, String sopUid) {
        UUID activeFacility = HieInteropService.requireTenantFacilityId();
        validateDicomUid(studyUid, "StudyInstanceUID");
        if (seriesUid != null && !seriesUid.isBlank()) {
            validateDicomUid(seriesUid, "SeriesInstanceUID");
        }
        if (sopUid != null && !sopUid.isBlank()) {
            validateDicomUid(sopUid, "SOPInstanceUID");
        }
        Optional<DicomStudyEntity> studyOpt = dicomRepo.findFirstByFacilityIdAndStudyInstanceUid(activeFacility, studyUid);
        if (studyOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!pacsEnabled || pacsBaseUrl == null || pacsBaseUrl.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"error\":\"WADO-RS binary retrieval requires healthcore.hie.dicom.pacs-enabled=true\",\"studyInstanceUid\":\""
                            + studyUid + "\"}").getBytes(StandardCharsets.UTF_8));
        }

        String path = seriesUid != null && sopUid != null
                ? "/studies/" + encode(studyUid) + "/series/" + encode(seriesUid) + "/instances/" + encode(sopUid)
                : "/studies/" + encode(studyUid);
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(normalizedBase() + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", sopUid != null ? "application/dicom" : "multipart/related; type=\"application/dicom\"");
            applyAuth(req);
            HttpResponse<byte[]> resp = httpClient.send(req.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 404) {
                return ResponseEntity.notFound().build();
            }
            String respContentType = resp.headers().firstValue("Content-Type").orElse("application/dicom");
            return ResponseEntity.status(resp.statusCode())
                    .header(HttpHeaders.CONTENT_TYPE, respContentType)
                    .body(resp.body());
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"error\":\"WADO-RS upstream unreachable\",\"detail\":\""
                            + Objects.toString(ex.getMessage(), "timeout").replace("\"", "'") + "\"}")
                            .getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Executes tenant-scoped QIDO-RS study search against the facility's authorized DICOM registry (and enriches via PACS when enabled). */
    @Transactional(readOnly = true)
    @CircuitBreaker(name = "pacsProxy")
    public Object queryQidoStudies(UUID patientId, String modality) {
        UUID activeFacility = HieInteropService.requireTenantFacilityId();
        List<DicomStudyEntity> local = patientId != null
                ? dicomRepo.findByFacilityIdAndPatientIdOrderByStudyDateDesc(activeFacility, patientId)
                : dicomRepo.findByFacilityIdOrderByStudyDateDesc(activeFacility);
        if (modality != null && !modality.isBlank()) {
            local = local.stream().filter(s -> modality.equalsIgnoreCase(s.getModality())).toList();
        }
        // Only query upstream PACS for studies explicitly owned by this facility tenant (never leak cross-tenant PACS studies).
        if (pacsEnabled && pacsBaseUrl != null && !pacsBaseUrl.isBlank() && !local.isEmpty()) {
            try {
                String uidList = local.stream()
                        .map(DicomStudyEntity::getStudyInstanceUid)
                        .filter(Objects::nonNull)
                        .distinct()
                        .limit(50)
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
                if (!uidList.isBlank()) {
                    StringBuilder q = new StringBuilder(normalizedBase())
                            .append("/studies?StudyInstanceUID=").append(encode(uidList));
                    if (modality != null && !modality.isBlank()) {
                        q.append("&Modality=").append(encode(modality));
                    }
                    HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(q.toString()))
                            .timeout(Duration.ofSeconds(12))
                            .header("Accept", "application/dicom+json");
                    applyAuth(req);
                    HttpResponse<String> resp = httpClient.send(req.GET().build(), HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                        return ResponseEntity.ok()
                                .contentType(MediaType.parseMediaType("application/dicom+json"))
                                .body(resp.body());
                    }
                }
            } catch (Exception ignored) {
                // Fall back to tenant-scoped local DICOM registry
            }
        }
        return local;
    }

    private String buildWadoStudyUrl(String studyUid) {
        if (pacsBaseUrl != null && !pacsBaseUrl.isBlank()) {
            return normalizedBase() + "/studies/" + studyUid;
        }
        return "/api/v1/hie/dicom/pacs/studies/" + studyUid;
    }

    private String normalizedBase() {
        return pacsBaseUrl.endsWith("/") ? pacsBaseUrl.substring(0, pacsBaseUrl.length() - 1) : pacsBaseUrl;
    }

    private void applyAuth(HttpRequest.Builder req) {
        if (pacsAuthHeader != null && !pacsAuthHeader.isBlank()) {
            req.header("Authorization", pacsAuthHeader);
        }
    }

    private static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    @RestController
    @RequestMapping("/api/v1/hie/dicom/pacs")
    @Tag(name = "DICOMweb Binary PACS Proxy", description = "STOW-RS binary upload, WADO-RS streaming & QIDO-RS PACS query proxy")
    public static class DicomPacsController {
        private final DicomPacsProxyService proxy;

        public DicomPacsController(DicomPacsProxyService proxy) {
            this.proxy = proxy;
        }

        @PostMapping(value = "/studies", consumes = {"application/dicom", "multipart/related", "application/octet-stream"})
        @PreAuthorize("hasAuthority('PERM_dicom:index') or hasAuthority('PERM_imaging:report:sign')")
        @Operation(summary = "Store binary DICOM study (STOW-RS) into external PACS and index StudyInstanceUID")
        public Map<String, Object> stowBinary(
                @RequestHeader("X-Facility-Id") UUID facilityId,
                @RequestHeader("X-Patient-Id") UUID patientId,
                @RequestHeader("X-Study-Instance-Uid") String studyInstanceUid,
                @RequestHeader(value = "X-Modality", defaultValue = "DX") String modality,
                @RequestHeader(value = "X-Study-Description", defaultValue = "Radiology Study") String description,
                @RequestHeader(value = "Content-Type", defaultValue = "application/dicom") String contentType,
                @RequestBody byte[] payload) {
            return proxy.storeBinaryStudy(facilityId, patientId, studyInstanceUid, modality, description, contentType, payload);
        }

        @GetMapping("/studies/{studyUid}")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "Retrieve tenant-scoped WADO-RS study binary")
        public ResponseEntity<byte[]> wadoStudy(@PathVariable String studyUid) {
            return proxy.retrieveWadoBinary(studyUid, null, null);
        }

        @GetMapping("/studies/{studyUid}/series/{seriesUid}/instances/{sopUid}")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "Retrieve tenant-scoped WADO-RS single DICOM SOP instance")
        public ResponseEntity<byte[]> wadoInstance(
                @PathVariable String studyUid,
                @PathVariable String seriesUid,
                @PathVariable String sopUid) {
            return proxy.retrieveWadoBinary(studyUid, seriesUid, sopUid);
        }

        @GetMapping("/qido")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "Query tenant-scoped QIDO-RS studies across upstream PACS or local metadata registry")
        public Object qidoQuery(
                @RequestParam(required = false) UUID patientId,
                @RequestParam(required = false) String modality) {
            return proxy.queryQidoStudies(patientId, modality);
        }
    }
}
