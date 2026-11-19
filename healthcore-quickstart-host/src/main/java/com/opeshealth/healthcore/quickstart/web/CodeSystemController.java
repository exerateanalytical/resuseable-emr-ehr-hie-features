/*
 * =============================================================================
 * Opes HealthCore SDK — Terminology & CodeSystem Validation REST Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.terminology.domain.TerminologyEntities.CodeConceptEntity;
import com.opeshealth.healthcore.terminology.domain.TerminologyEntities.ConceptMapEntity;
import com.opeshealth.healthcore.terminology.service.TerminologyService;
import com.opeshealth.healthcore.terminology.service.TerminologyService.BatchValidationResult;
import com.opeshealth.healthcore.terminology.service.TerminologyService.CodeSystemDescriptor;
import com.opeshealth.healthcore.terminology.service.TerminologyService.CodeValidationRequest;
import com.opeshealth.healthcore.terminology.service.TerminologyService.CodeValidationResult;
import com.opeshealth.healthcore.terminology.service.TerminologyService.SubsumptionOutcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * REST and HL7 FHIR R4 terminology validation controller ({@code CodeSystemController})
 * supporting LOINC, SNOMED-CT, ICD-10, ICD-11, RxNorm, ATC, and CVX code validation,
 * concept lookup, IS-A subsumption checks, crosswalk translation, and UCUM unit conversion.
 */
@RestController
@Tag(
        name = "FHIR R4 Terminology & CodeSystem Validation",
        description = "LOINC, SNOMED-CT, and ICD-10 clinical terminology validation ($validate-code), concept lookup ($lookup), IS-A hierarchy subsumption ($subsumes), and crosswalk translation ($translate)"
)
public class CodeSystemController {

    private static final String FHIR_JSON = "application/fhir+json";

    public record ConceptUpsertPayload(
            String system,
            String code,
            String displayEn,
            String displayFr,
            String clinicalDomain,
            String ucumUnit
    ) {}

    public record UcumConversionPayload(
            BigDecimal value,
            String fromUnit,
            String toUnit
    ) {}

    private final TerminologyService terminologyService;

    public CodeSystemController(TerminologyService terminologyService) {
        this.terminologyService = Objects.requireNonNull(terminologyService, "TerminologyService is required");
    }

    @GetMapping(
            value = {"/api/v1/terminology/code-systems", "/api/v1/fhir/CodeSystem"},
            produces = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON}
    )
    @Operation(summary = "List supported clinical code systems (LOINC, SNOMED-CT, ICD-10, ICD-11, RxNorm, ATC, CVX)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Supported clinical code systems returned"),
            @ApiResponse(responseCode = "401", description = "Unauthorized request")
    })
    @ClinicalAudit(action = "CODESYSTEM_CATALOG_LIST", resourceType = "CodeSystem")
    public ResponseEntity<Map<String, Object>> listCodeSystems(
            @RequestHeader(value = "Accept", required = false) String acceptHeader,
            HttpServletResponse response) {
        List<CodeSystemDescriptor> systems = terminologyService.listSupportedCodeSystems();
        applyVersionEtag(response, systems.size());
        if (wantsFhirJson(acceptHeader)) {
            List<Map<String, Object>> entries = systems.stream()
                    .map(cs -> Map.<String, Object>of(
                            "fullUrl", "CodeSystem/" + cs.id(),
                            "resource", toFhirCodeSystemMap(cs)
                    ))
                    .toList();
            Map<String, Object> bundle = new LinkedHashMap<>();
            bundle.put("resourceType", "Bundle");
            bundle.put("type", "searchset");
            bundle.put("total", entries.size());
            bundle.put("entry", entries);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(FHIR_JSON)).body(bundle);
        }
        return ResponseEntity.ok(Map.of(
                "total", systems.size(),
                "codeSystems", systems
        ));
    }

    @GetMapping(
            value = {"/api/v1/terminology/code-systems/{systemId}", "/api/v1/fhir/CodeSystem/{systemId}"},
            produces = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON}
    )
    @Operation(summary = "Read a specific clinical CodeSystem descriptor by short ID or alias (loinc, snomed-ct, icd-10)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "CodeSystem metadata found"),
            @ApiResponse(responseCode = "404", description = "CodeSystem not found")
    })
    @ClinicalAudit(action = "CODESYSTEM_READ", resourceType = "CodeSystem")
    public ResponseEntity<Map<String, Object>> getCodeSystemById(
            @PathVariable String systemId,
            HttpServletResponse response) {
        String normalizedUri = TerminologyService.normalizeSystemUri(systemId);
        CodeSystemDescriptor descriptor = terminologyService.listSupportedCodeSystems().stream()
                .filter(cs -> cs.id().equalsIgnoreCase(systemId)
                        || cs.name().equalsIgnoreCase(systemId)
                        || (normalizedUri != null && cs.systemUri().equalsIgnoreCase(normalizedUri)))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("CodeSystem not found: " + systemId));
        applyVersionEtag(response, 1L);
        return ResponseEntity.ok(toFhirCodeSystemMap(descriptor));
    }

    @GetMapping(
            value = {
                    "/api/v1/terminology/validate-code",
                    "/api/v1/fhir/CodeSystem/$validate-code",
                    "/api/v1/fhir/ValueSet/$validate-code"
            },
            produces = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON}
    )
    @Operation(summary = "Validate a clinical code against LOINC, SNOMED-CT, ICD-10, or RxNorm ($validate-code GET)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Validation completed and result returned"),
            @ApiResponse(responseCode = "400", description = "Missing required parameters")
    })
    @ClinicalAudit(action = "CODESYSTEM_VALIDATE_CODE", resourceType = "CodeSystem")
    public ResponseEntity<?> validateCodeGet(
            @RequestParam(name = "system", required = false) String system,
            @RequestParam(name = "url", required = false) String url,
            @RequestParam(name = "code") String code,
            @RequestParam(name = "display", required = false) String display,
            @RequestParam(name = "expectedDomain", required = false) String expectedDomain,
            @RequestParam(name = "ucumUnit", required = false) String ucumUnit,
            @RequestParam(name = "strictDisplay", defaultValue = "false") boolean strictDisplay,
            @RequestParam(name = "format", defaultValue = "json") String format,
            @RequestHeader(value = "Accept", required = false) String acceptHeader,
            HttpServletResponse response) {
        String targetSystem = (system != null && !system.isBlank()) ? system : url;
        CodeValidationResult result = terminologyService.validateCode(
                new CodeValidationRequest(targetSystem, code, display, expectedDomain, ucumUnit, strictDisplay));
        applyVersionEtag(response, 1L);
        if ("fhir".equalsIgnoreCase(format) || wantsFhirJson(acceptHeader)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(FHIR_JSON))
                    .body(toFhirValidationParameters(result));
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping(
            value = {
                    "/api/v1/terminology/validate-code",
                    "/api/v1/fhir/CodeSystem/$validate-code",
                    "/api/v1/fhir/ValueSet/$validate-code"
            },
            consumes = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON},
            produces = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON}
    )
    @Operation(summary = "Validate a clinical concept payload against LOINC, SNOMED-CT, ICD-10, or RxNorm ($validate-code POST)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Validation outcome returned"),
            @ApiResponse(responseCode = "400", description = "Malformed request body")
    })
    @ClinicalAudit(action = "CODESYSTEM_VALIDATE_CODE_POST", resourceType = "CodeSystem")
    public ResponseEntity<?> validateCodePost(
            @RequestBody CodeValidationRequest request,
            @RequestParam(name = "format", defaultValue = "json") String format,
            @RequestHeader(value = "Accept", required = false) String acceptHeader,
            HttpServletResponse response) {
        CodeValidationResult result = terminologyService.validateCode(request);
        applyVersionEtag(response, 1L);
        if ("fhir".equalsIgnoreCase(format) || wantsFhirJson(acceptHeader)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(FHIR_JSON))
                    .body(toFhirValidationParameters(result));
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping(
            value = "/api/v1/terminology/validate-batch",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Batch-validate multiple clinical codes across LOINC, SNOMED-CT, ICD-10, and RxNorm")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Batch terminology validation results returned"),
            @ApiResponse(responseCode = "400", description = "Empty or malformed batch validation list")
    })
    @ClinicalAudit(action = "CODESYSTEM_VALIDATE_BATCH", resourceType = "CodeSystem")
    public ResponseEntity<BatchValidationResult> validateBatch(
            @RequestBody List<CodeValidationRequest> requests,
            HttpServletResponse response) {
        BatchValidationResult batchResult = terminologyService.validateBatch(requests);
        applyVersionEtag(response, batchResult.totalChecked());
        return ResponseEntity.ok(batchResult);
    }

    @GetMapping(
            value = {"/api/v1/terminology/lookup", "/api/v1/fhir/CodeSystem/$lookup"},
            produces = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON}
    )
    @Operation(summary = "Lookup a coded concept in LOINC, SNOMED-CT, ICD-10, or RxNorm ($lookup)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Concept found and details returned"),
            @ApiResponse(responseCode = "404", description = "Concept not found in specified code system")
    })
    @ClinicalAudit(action = "CODESYSTEM_LOOKUP", resourceType = "CodeSystem")
    public ResponseEntity<?> lookupConcept(
            @RequestParam String system,
            @RequestParam String code,
            @RequestParam(name = "format", defaultValue = "json") String format,
            @RequestHeader(value = "Accept", required = false) String acceptHeader,
            HttpServletResponse response) {
        String normalizedSystem = TerminologyService.normalizeSystemUri(system);
        String resolvedSystem = normalizedSystem != null ? normalizedSystem : system;
        CodeConceptEntity entity = terminologyService.lookup(resolvedSystem, code)
                .orElseThrow(() -> new NoSuchElementException(
                        "Concept '" + code + "' not found in code system '" + resolvedSystem + "'"));
        applyVersionEtag(response, 1L);
        if ("fhir".equalsIgnoreCase(format) || wantsFhirJson(acceptHeader)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(FHIR_JSON))
                    .body(toFhirLookupParameters(entity));
        }
        return ResponseEntity.ok(entity);
    }

    @GetMapping(
            value = {"/api/v1/terminology/subsumes", "/api/v1/fhir/CodeSystem/$subsumes"},
            produces = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON}
    )
    @Operation(summary = "Test IS-A hierarchical subsumption between two concepts ($subsumes)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Subsumption relationship evaluated (equivalent | subsumes | subsumed-by | not-subsumed)"),
            @ApiResponse(responseCode = "400", description = "Invalid code system or missing concept codes")
    })
    @ClinicalAudit(action = "CODESYSTEM_SUBSUMES", resourceType = "CodeSystem")
    public ResponseEntity<?> evaluateSubsumption(
            @RequestParam String system,
            @RequestParam String codeA,
            @RequestParam String codeB,
            @RequestParam(name = "format", defaultValue = "json") String format,
            @RequestHeader(value = "Accept", required = false) String acceptHeader,
            HttpServletResponse response) {
        SubsumptionOutcome outcome = terminologyService.evaluateSubsumption(system, codeA, codeB);
        applyVersionEtag(response, 1L);
        if ("fhir".equalsIgnoreCase(format) || wantsFhirJson(acceptHeader)) {
            Map<String, Object> fhirParams = Map.of(
                    "resourceType", "Parameters",
                    "parameter", List.of(Map.of("name", "outcome", "valueCode", outcome.outcome()))
            );
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(FHIR_JSON)).body(fhirParams);
        }
        return ResponseEntity.ok(outcome);
    }

    @GetMapping(
            value = {"/api/v1/terminology/translate", "/api/v1/fhir/ConceptMap/$translate"},
            produces = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON}
    )
    @Operation(summary = "Translate a clinical concept across code systems, such as SNOMED-CT to WHO ICD-10 ($translate)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Crosswalk translation completed"),
            @ApiResponse(responseCode = "400", description = "Invalid source system or code")
    })
    @ClinicalAudit(action = "CONCEPTMAP_TRANSLATE", resourceType = "ConceptMap")
    public ResponseEntity<Map<String, Object>> translateConcept(
            @RequestParam String system,
            @RequestParam String code,
            HttpServletResponse response) {
        String normalizedSystem = TerminologyService.normalizeSystemUri(system);
        String resolvedSystem = normalizedSystem != null ? normalizedSystem : system;
        List<ConceptMapEntity> mappings = terminologyService.translate(resolvedSystem, code);
        applyVersionEtag(response, mappings.size());
        return ResponseEntity.ok(Map.of(
                "result", !mappings.isEmpty(),
                "sourceSystem", resolvedSystem,
                "sourceCode", code,
                "matchCount", mappings.size(),
                "matches", mappings
        ));
    }

    @PostMapping(
            value = "/api/v1/terminology/concepts",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Register or update a syntax-validated clinical concept in LOINC, SNOMED-CT, ICD-10, or RxNorm")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Concept validated and persisted"),
            @ApiResponse(responseCode = "400", description = "Code syntax violation or missing display name")
    })
    @ClinicalAudit(action = "CODESYSTEM_CONCEPT_UPSERT", resourceType = "CodeSystem")
    public ResponseEntity<CodeConceptEntity> upsertConcept(
            @RequestBody ConceptUpsertPayload payload,
            HttpServletResponse response) {
        CodeConceptEntity saved = terminologyService.registerOrUpdateConcept(
                payload.system(),
                payload.code(),
                payload.displayEn(),
                payload.displayFr(),
                payload.clinicalDomain(),
                payload.ucumUnit());
        applyVersionEtag(response, 1L);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PatchMapping(
            value = "/api/v1/terminology/concepts/deactivate",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Mark a deprecated concept as inactive so clinical validation rejects it")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Concept marked inactive"),
            @ApiResponse(responseCode = "404", description = "Concept not found")
    })
    @ClinicalAudit(action = "CODESYSTEM_CONCEPT_DEACTIVATE", resourceType = "CodeSystem")
    public ResponseEntity<CodeConceptEntity> deactivateConcept(
            @RequestParam String system,
            @RequestParam String code,
            HttpServletResponse response) {
        CodeConceptEntity updated = terminologyService.deactivateConcept(system, code);
        applyVersionEtag(response, 2L);
        return ResponseEntity.ok(updated);
    }

    @PostMapping(
            value = "/api/v1/terminology/convert-ucum",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Convert clinical quantities between standard UCUM units (mg/dL <-> mmol/L, Cel <-> [degF], kg <-> [lb_av])")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "UCUM unit conversion succeeded"),
            @ApiResponse(responseCode = "400", description = "Incompatible or unsupported UCUM unit pair")
    })
    @ClinicalAudit(action = "TERMINOLOGY_UCUM_CONVERT", resourceType = "CodeSystem")
    public ResponseEntity<Map<String, Object>> convertUcum(
            @RequestBody UcumConversionPayload payload,
            HttpServletResponse response) {
        BigDecimal converted = terminologyService.convertUcum(payload.value(), payload.fromUnit(), payload.toUnit());
        applyVersionEtag(response, 1L);
        return ResponseEntity.ok(Map.of(
                "inputValue", payload.value(),
                "fromUnit", payload.fromUnit(),
                "toUnit", payload.toUnit(),
                "convertedValue", converted,
                "system", TerminologyService.UCUM
        ));
    }

    private static Map<String, Object> toFhirCodeSystemMap(CodeSystemDescriptor cs) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("resourceType", "CodeSystem");
        resource.put("id", cs.id());
        resource.put("url", cs.systemUri());
        resource.put("version", cs.version());
        resource.put("name", cs.name());
        resource.put("title", cs.title());
        resource.put("status", "active");
        resource.put("publisher", cs.publisher());
        resource.put("content", "fragment");
        resource.put("count", cs.activeConceptCount());
        resource.put("syntaxRule", cs.syntaxRule());
        return resource;
    }

    private static Map<String, Object> toFhirValidationParameters(CodeValidationResult res) {
        List<Map<String, Object>> params = new ArrayList<>();
        params.add(Map.of("name", "result", "valueBoolean", res.valid()));
        params.add(Map.of("name", "active", "valueBoolean", res.active()));
        params.add(Map.of("name", "system", "valueUri", res.normalizedSystemUri()));
        params.add(Map.of("name", "code", "valueCode", res.code()));
        if (res.canonicalDisplayEn() != null) {
            params.add(Map.of("name", "display", "valueString", res.canonicalDisplayEn()));
        }
        params.add(Map.of("name", "issueCode", "valueCode", res.issueCode()));
        params.add(Map.of("name", "severity", "valueCode", res.severity()));
        params.add(Map.of("name", "message", "valueString", res.message()));
        return Map.of(
                "resourceType", "Parameters",
                "parameter", params
        );
    }

    private static Map<String, Object> toFhirLookupParameters(CodeConceptEntity e) {
        List<Map<String, Object>> params = new ArrayList<>();
        params.add(Map.of("name", "name", "valueString", TerminologyService.resolveCodeSystemName(e.getSystemUri())));
        params.add(Map.of("name", "system", "valueUri", e.getSystemUri()));
        params.add(Map.of("name", "code", "valueCode", e.getCode()));
        params.add(Map.of("name", "display", "valueString", e.getDisplayEn()));
        params.add(Map.of("name", "active", "valueBoolean", e.isActive()));
        if (e.getDisplayFr() != null) {
            params.add(Map.of("name", "displayFr", "valueString", e.getDisplayFr()));
        }
        if (e.getClinicalDomain() != null) {
            params.add(Map.of("name", "clinicalDomain", "valueCode", e.getClinicalDomain()));
        }
        if (e.getUcumUnit() != null) {
            params.add(Map.of("name", "ucumUnit", "valueCode", e.getUcumUnit()));
        }
        return Map.of(
                "resourceType", "Parameters",
                "parameter", params
        );
    }

    private static boolean wantsFhirJson(String acceptHeader) {
        return acceptHeader != null && acceptHeader.toLowerCase(Locale.ROOT).contains("application/fhir+json");
    }

    private static void applyVersionEtag(HttpServletResponse response, long version) {
        if (response != null) {
            response.setHeader("ETag", "W/\"" + version + "\"");
        }
    }
}
