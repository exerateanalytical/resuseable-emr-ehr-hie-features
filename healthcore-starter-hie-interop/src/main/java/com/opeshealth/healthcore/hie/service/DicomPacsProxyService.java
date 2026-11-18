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
        DicomStudyEntity entity = dicomRepo.findFirstByStudyInstanceUid(studyInstanceUid)
                .orElseGet(DicomStudyEntity::new);
        entity.setFacilityId(facilityId);
        entity.setPatientId(patientId);
        entity.setStudyInstanceUid(studyInstanceUid);
        entity.setModality(modality != null && !modality.isBlank() ? modality : "DX");
        entity.setStudyDescription(studyDescription != null ? studyDescription : "DICOMweb STOW-RS Study");
        String resolvedWado = buildWadoStudyUrl(studyInstanceUid);
        entity.setWadoRsUrl(resolvedWado);
        DicomStudyEntity saved = dicomRepo.save(entity);

        if (!pacsEnabled || pacsBaseUrl == null || pacsBaseUrl.isBlank()) {
            return Map.of(
                    "status", "INDEXED_LOCAL",
                    "studyId", saved.getId(),
                    "studyInstanceUid", saved.getStudyInstanceUid(),
                    "wadoRsUrl", resolvedWado,
                    "bytesReceived", dicomBytes != null ? dicomBytes.length : 0
            );
        }

        try {
            String target = normalizedBase() + "/studies";
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", contentType != null && !contentType.isBlank()
                            ? contentType
                            : "application/dicom");
            applyAuth(req);
            HttpResponse<String> resp = httpClient.send(
                    req.POST(HttpRequest.BodyPublishers.ofByteArray(dicomBytes != null ? dicomBytes : new byte[0])).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("PACS STOW-RS HTTP " + resp.statusCode());
            }
            return Map.of(
                    "status", "FORWARDED_TO_PACS",
                    "pacsHttpStatus", resp.statusCode(),
                    "studyId", saved.getId(),
                    "studyInstanceUid", saved.getStudyInstanceUid(),
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
        HieDlqMessageEntity dlq = new HieDlqMessageEntity();
        dlq.setProtocol("DICOM");
        dlq.setExternalMessageId(studyInstanceUid);
        dlq.setRawPayload("{\"facilityId\":\"" + facilityId + "\",\"patientId\":\"" + patientId
                + "\",\"bytes\":" + (dicomBytes != null ? dicomBytes.length : 0) + "}");
        dlq.setErrorDetail(ex.getMessage());
        dlqRepo.save(dlq);
        return Map.of(
                "status", "QUARANTINED_TO_DLQ",
                "studyInstanceUid", studyInstanceUid,
                "reason", Objects.toString(ex.getMessage(), "PACS unavailable")
        );
    }

    /** Retrieves binary DICOM study or instance bytes via WADO-RS from upstream PACS. */
    @CircuitBreaker(name = "pacsProxy")
    @Bulkhead(name = "hieOutbound")
    public ResponseEntity<byte[]> retrieveWadoBinary(String studyUid, String seriesUid, String sopUid) {
        Optional<DicomStudyEntity> studyOpt = dicomRepo.findFirstByStudyInstanceUid(studyUid);
        if (!pacsEnabled || pacsBaseUrl == null || pacsBaseUrl.isBlank()) {
            if (studyOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            String json = "{\"studyInstanceUid\":\"" + studyUid + "\",\"wadoRsUrl\":\""
                    + Objects.toString(studyOpt.get().getWadoRsUrl(), "") + "\",\"pacsProxy\":\"DISABLED\"}";
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.getBytes(StandardCharsets.UTF_8));
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

    /** Executes QIDO-RS study search upstream on PACS or falls back to local DICOM registry. */
    @Transactional(readOnly = true)
    @CircuitBreaker(name = "pacsProxy")
    public Object queryQidoStudies(UUID patientId, String modality) {
        if (pacsEnabled && pacsBaseUrl != null && !pacsBaseUrl.isBlank()) {
            try {
                StringBuilder q = new StringBuilder(normalizedBase()).append("/studies?");
                if (patientId != null) q.append("PatientID=").append(encode(patientId.toString())).append('&');
                if (modality != null && !modality.isBlank()) q.append("Modality=").append(encode(modality));
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
            } catch (Exception ignored) {
                // Fall back to local HIE DICOM registry
            }
        }
        List<DicomStudyEntity> local = patientId != null
                ? dicomRepo.findByPatientIdOrderByStudyDateDesc(patientId)
                : dicomRepo.findAll();
        if (modality != null && !modality.isBlank()) {
            local = local.stream().filter(s -> modality.equalsIgnoreCase(s.getModality())).toList();
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
        @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_dicom:index')")
        @Operation(summary = "Retrieve WADO-RS study binary / metadata receipt")
        public ResponseEntity<byte[]> wadoStudy(@PathVariable String studyUid) {
            return proxy.retrieveWadoBinary(studyUid, null, null);
        }

        @GetMapping("/studies/{studyUid}/series/{seriesUid}/instances/{sopUid}")
        @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_dicom:index')")
        @Operation(summary = "Retrieve WADO-RS single DICOM SOP instance")
        public ResponseEntity<byte[]> wadoInstance(
                @PathVariable String studyUid,
                @PathVariable String seriesUid,
                @PathVariable String sopUid) {
            return proxy.retrieveWadoBinary(studyUid, seriesUid, sopUid);
        }

        @GetMapping("/qido")
        @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_dicom:index')")
        @Operation(summary = "Query QIDO-RS studies across upstream PACS with local fallback")
        public Object qidoQuery(
                @RequestParam(required = false) UUID patientId,
                @RequestParam(required = false) String modality) {
            return proxy.queryQidoStudies(patientId, modality);
        }
    }
}
