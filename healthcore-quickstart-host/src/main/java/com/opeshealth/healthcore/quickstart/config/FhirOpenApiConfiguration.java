/*
 * =============================================================================
 * Opes HealthCore SDK — Multi-RDBMS Enterprise Healthcare Platform
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.method.HandlerMethod;

import java.util.List;
import java.util.Map;

/**
 * Configures SpringDoc OpenAPI 3.1 (Swagger UI & {@code /v3/api-docs}) in the server module
 * to automatically generate comprehensive API documentation for all HL7 FHIR R4 and CDS Hooks 2.0
 * endpoints, including resource schemas, security schemes, rate-limit headers, and HTTP status codes.
 */
@Configuration
public class FhirOpenApiConfiguration {

    public static final String DEFAULT_ISSUER_URI = "http://localhost:8085/realms/opes-healthcore";
    public static final String SECURITY_SCHEME_BEARER_JWT = "bearer-jwt";
    public static final String SECURITY_SCHEME_BEARER_AUTH = "bearerAuth";
    public static final String SECURITY_SCHEME_SMART_OAUTH2 = "smartOAuth2";
    public static final String SECURITY_SCHEME_FACILITY_HEADER = "facility-tenant-header";
    public static final String FHIR_JSON_MEDIA_TYPE = "application/fhir+json";
    public static final String JSON_MEDIA_TYPE = "application/json";

    public OpenAPI healthCoreFhirOpenApi() {
        return healthCoreFhirOpenApi(DEFAULT_ISSUER_URI);
    }

    @Bean
    @Primary
    public OpenAPI healthCoreFhirOpenApi(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:" + DEFAULT_ISSUER_URI + "}") String issuerUri) {
        String resolvedIssuer = (issuerUri == null || issuerUri.isBlank()) ? DEFAULT_ISSUER_URI : issuerUri.trim();
        Components components = new Components()
                .addSecuritySchemes(SECURITY_SCHEME_BEARER_JWT, new SecurityScheme()
                        .name(SECURITY_SCHEME_BEARER_JWT)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("SMART-on-FHIR v2 / Keycloak OIDC Bearer JWT access token"))
                .addSecuritySchemes(SECURITY_SCHEME_BEARER_AUTH, new SecurityScheme()
                        .name(SECURITY_SCHEME_BEARER_AUTH)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Bearer JWT token for clinical REST & GraphQL APIs"))
                .addSecuritySchemes(SECURITY_SCHEME_SMART_OAUTH2, new SecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2)
                        .flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                                .authorizationUrl(resolvedIssuer + "/protocol/openid-connect/auth")
                                .tokenUrl(resolvedIssuer + "/protocol/openid-connect/token")
                                .scopes(new Scopes()
                                        .addString("openid", "OpenID Connect identity")
                                        .addString("fhirUser", "FHIR Practitioner or Patient user")
                                        .addString("patient/*.rs", "SMART v2 patient read/search")
                                        .addString("user/*.cruds", "SMART v2 clinician CRUD & search")))))
                .addSecuritySchemes(SECURITY_SCHEME_FACILITY_HEADER, new SecurityScheme()
                        .name("X-Facility-Id")
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .description("UUID of the healthcare facility tenant executing the clinical operation"))
                .addSchemas("FhirPatientResource", buildFhirPatientSchema())
                .addSchemas("FhirPractitionerResource", buildFhirPractitionerSchema())
                .addSchemas("FhirEncounterResource", buildFhirEncounterSchema())
                .addSchemas("FhirObservationResource", buildFhirObservationSchema())
                .addSchemas("FhirBundleResource", buildFhirBundleSchema())
                .addSchemas("FhirAuditEventResource", buildFhirAuditEventSchema())
                .addSchemas("FhirCodeSystemResource", buildFhirCodeSystemSchema())
                .addSchemas("FhirOperationOutcome", buildFhirOperationOutcomeSchema())
                .addSchemas("ApiErrorResponse", buildApiErrorResponseSchema());

        return new OpenAPI()
                .info(new Info()
                        .title("Opes HealthCore HL7 FHIR R4 & CDS Hooks 2.0 API")
                        .version("1.0.0")
                        .description("Enterprise HL7 FHIR R4 (Patient, Practitioner, Encounter, Observation, Bundle Batch/Transaction, AuditEvent), "
                                + "CDS Hooks 2.0 Clinical Decision Support, and GraphQL Healthcare Interoperability API. "
                                + "All clinical endpoints enforce HAPI FHIR R4 structural validation, Bucket4j token-bucket rate limiting, "
                                + "and HIPAA/GDPR SHA-256 tamper-evident audit logging.")
                        .contact(new Contact()
                                .name("Jude Nshome — Opes Health Systems")
                                .email("nshome@opesware.com")
                                .url("https://www.opeshealthsystems.com"))
                        .license(new License()
                                .name("Apache-2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .externalDocs(new ExternalDocumentation()
                        .description("HL7 FHIR R4 Specification & CDS Hooks 2.0 Reference")
                        .url("https://hl7.org/fhir/R4/"))
                .servers(List.of(
                        new Server().url("http://localhost:3000").description("Opes HealthCore Local / Container Server (Port 3000)")
                ))
                .tags(List.of(
                        new Tag().name("FHIR R4 Patient").description("Master Patient Index (MPI) & HL7 FHIR R4 Patient CRUD and multi-criteria search endpoints"),
                        new Tag().name("FHIR R4 Practitioner").description("HL7 FHIR R4 Practitioner directory CRUD, qualification, and identifier search endpoints"),
                        new Tag().name("FHIR R4 Encounter").description("HL7 FHIR R4 Encounter lifecycle CRUD, status transitions, and CDS Hooks 2.0 evaluation"),
                        new Tag().name("FHIR R4 Observation").description("HL7 FHIR R4 Observation (vital signs & laboratory results) CRUD, LOINC/UCUM validation, and search"),
                        new Tag().name("FHIR R4 Bundle (Batch & Transaction)").description("HL7 FHIR R4 Bundle processor supporting atomic transactions (urn:uuid resolution) and isolated batch operations"),
                        new Tag().name("FHIR R4 AuditEvent & HIPAA/GDPR Compliance").description("Tamper-evident SHA-256 hash-chained FHIR R4 AuditEvent search, chain integrity verification, and HIPAA § 164.528 / GDPR Art. 15 patient access disclosure reports"),
                        new Tag().name("FHIR R4 Terminology & CodeSystem Validation").description("LOINC, SNOMED-CT, and ICD-10 clinical terminology validation ($validate-code), concept lookup ($lookup), IS-A hierarchy subsumption ($subsumes), and crosswalk translation ($translate)"),
                        new Tag().name("FHIR R4 Cold-Chain & Vaccine Sensor Telemetry").description("Vaccine and pharmaceutical cold-chain storage & transport telemetry integrated with HL7 FHIR R4 Observation, automated excursion alerting, and Mean Kinetic Temperature (MKT) compliance"),
                        new Tag().name("FHIR R4 Bulk Data Export ($export)").description("HL7 FHIR R4 Bulk Data Access IG v2.0 cohort export for practitioners (/Practitioner/{id}/$export and /Patient/$export) producing application/fhir+ndjson streams and status manifests"),
                        new Tag().name("FHIR R4 Patient Identity Matching & Duplicate Resolution").description("Probabilistic Fellegi-Sunter & Jaro-Winkler Patient/$match, database duplicate scanning, and manual resolution queue"),
                        new Tag().name("FHIR R4 Next-Generation Digital Health Suite").description("FHIR Consent/DS4P Break-the-Glass, CPIC Pharmacogenomics, RPM NEWS2/qSOFA, R5-Backport Subscriptions, Closed-Loop BCMA, WHO AWaRe Antimicrobial Stewardship, Signed IPS ($summary), Da Vinci PAS Prior Auth, PROMs, and Gravity SDoH"),
                        new Tag().name("FHIR R4 Digital Health Certificates & JWS QR Verification").description("JWS signed (RS256 & Ed25519) health certificates, SMART Health Card (shc:/) QR code generation (SVG/PNG), mobile verification, and RFC 7517 JWKS"),
                        new Tag().name("FHIR R4 International Patient Summary ($summary) & JWS QR Payloads").description("HL7 FHIR R4 Patient/{id}/$summary operation generating JWS-signed (RS256 & Ed25519) IPS Document Bundles, SMART Health Card (shc:/) QR payloads, SVG/PNG QR codes, and cryptographic verification"),
                        new Tag().name("FHIR R4 Consent, DS4P Privacy & Break-the-Glass").description("HL7 FHIR R4 Consent lifecycle, DS4P Data Segmentation for Privacy (HIV, PSY, ETH, SDV, GEN, VIP), and Break-the-Glass (ETREAT) emergency access with mandatory HIPAA/GDPR audit flagging"),
                        new Tag().name("FHIR R4 SMART Health Links (shlink:/)").description("AES-256-GCM encrypted SMART Health Links, passcode-protected manifests, live-updating patient sharing links, revocation, and QR code rendering"),
                        new Tag().name("FHIR R4 Chart Portability ($everything & Compartments)").description("HL7 FHIR R4 Patient/{id}/$everything, Encounter/{id}/$everything, and Patient Compartment Search with DS4P privacy enforcement"),
                        new Tag().name("Federated HIE Exchange (UDAP / TEFCA / IHE XCPD & XCA)").description("X.509 Trust Anchors, UDAP Dynamic Client Registration, IHE XCPD Cross-Community Patient Discovery, and IHE XCA Cross-Community Query & Retrieve"),
                        new Tag().name("FHIR R4 Clinical Quality Measures (eCQM / $evaluate-measure)").description("HEDIS, CMS eCQM, and WHO quality indicator definitions and Measure/{id}/$evaluate-measure FHIR MeasureReport generation"),
                        new Tag().name("FHIR R4 Patient Messaging & Recall (SMS / WhatsApp / USSD)").description("Omnichannel FHIR Communication delivery, EN/FR clinical recall templates, opt-out consent controls, and feature-phone USSD sessions"),
                        new Tag().name("FHIR R4 Longitudinal CarePlans, Goals & Care-Gap Closure").description("Evidence-based chronic disease & maternal CarePlans (Diabetes, Hypertension, HIV ART, TB DOT, ANC, Heart Failure), Task closure, and overdue care-gap detection"),
                        new Tag().name("FHIR R4 Radiology Modality Worklist (DICOM MWL / MPPS & Critical Findings)").description("Imaging ServiceRequest scheduling into DICOM MWL, MPPS acquisition updates, structured FHIR DiagnosticReport sign-off, and closed-loop critical findings acknowledgment"),
                        new Tag().name("Clinical Research, Dynamic eConsent & OMOP CDM v5.4 Export").description("Study protocol management, dynamic eConsent opt-in/withdrawal, cohort eligibility screening, and Safe-Harbor OMOP CDM v5.4 export"),
                        new Tag().name("Offline-First Encrypted Media & Binary Attachment Sync").description("Resumable, chunked, SHA-256-verified encrypted binary uploads (wound photos, PDFs, ultrasound clips) for KMP mobile devices"),
                        new Tag().name("Payer Clearinghouse, EDI X12 (837 / 835) & Denial Appeals").description("Pre-submission claim scrubbing, EDI X12 837P/837I generation, EDI 835 remittance reconciliation, FHIR ExplanationOfBenefit, and CARC/RARC denial appeal workflows"),
                        new Tag().name("Integration Dead-Letter Queue (DLQ) & Webhook/MLLP Replay").description("Inspect, replay, bulk-replay, or dismiss failed FHIR Subscription webhooks, HL7v2 MLLP deliveries, transactional outbox events, and federated XCA calls"),
                        new Tag().name("Multi-Tenant SaaS Onboarding & Feature-Flag Governance").description("Self-service healthcare facility onboarding, archetype licensing (EMR/EHR/HIE/PIS/LIS), API quota plans, terminology pack versioning, and per-facility feature flags"),
                        new Tag().name("Clinical AI — Ambient Scribe & Voice-to-SOAP").description("Audio transcription (gemini-3.5-transcribe), Safe-Harbor PHI redaction, and structured SOAP + FHIR R4 draft generation requiring clinician attestation"),
                        new Tag().name("Clinical AI — Automated Medical Coding & DRG/UHC Billing").description("Extracts ICD-10, ICD-11, SNOMED-CT, LOINC, CPT, and DRG/UHC codes with clinical evidence spans and CDI queries"),
                        new Tag().name("Clinical AI — Longitudinal Chart & IPS Summarizer").description("Generates DS4P-aware SBAR handoff briefs, ED triage briefs, IPS executive summaries, and plain-language discharge summaries"),
                        new Tag().name("Clinical AI — Multimodal Diagnostic & Image Triage").description("Structured clinician-review triage of wound photos, dermatology captures, scanned lab slips, and ECG strips"),
                        new Tag().name("Clinical AI — Clinical Decision Support & Differential Assistant").description("Human-in-the-loop drug-disease, pharmacogenomics, sepsis cues, and differential workup synthesis"),
                        new Tag().name("Clinical AI — Prior Auth & Claim Denial Appeals").description("Drafts payer medical necessity narratives and CARC/RARC claim denial appeal letters from redacted patient chart evidence"),
                        new Tag().name("Clinical AI — Calibrated Triage & Policy Decisions (gemini-decision-1)").description("Calibrated yes/no (noul), ESI 1-5 acuity routing (choice), and medical necessity rubric (score) decisions with zero output tokens"),
                        new Tag().name("Clinical AI — Multilingual Patient Discharge & Voice Coach").description("Plain-language EN/FR discharge plans and optional WAV voice synthesis (gemini-3.8-flash-lite-tts) for low-literacy care coaching"),
                        new Tag().name("Clinical AI — Semantic Chart Search & Trial Cohort Matching").description("Safe-Harbor redacted clinical embeddings (gemini-embedding-2) and tenant-scoped cosine-similarity search across charts and trial protocols"),
                        new Tag().name("Clinical AI — PHI Redaction Gateway, Governance & Attestation Ledger").description("HIPAA Safe-Harbor 18-identifier redaction inspection, mandatory clinician sign-off on AI drafts, tenant AI kill-switch, and audit metrics"),
                        new Tag().name("CDS Hooks 2.0 Clinical Decision Support").description("HL7 CDS Hooks 2.0 Discovery, Hook Execution (patient-view, encounter-start, encounter-discharge, observation-review), and Feedback endpoints")
                ))
                .addSecurityItem(new SecurityRequirement()
                        .addList(SECURITY_SCHEME_BEARER_JWT)
                        .addList(SECURITY_SCHEME_FACILITY_HEADER))
                .components(components);
    }

    @Bean
    public GroupedOpenApi fhirEndpointsGroupedOpenApi(OperationCustomizer fhirStandardResponsesAndHeadersCustomizer) {
        return GroupedOpenApi.builder()
                .group("fhir-r4-and-cds-hooks")
                .displayName("HL7 FHIR R4 & CDS Hooks 2.0 API")
                .packagesToScan("com.opeshealth.healthcore.quickstart.web")
                .pathsToMatch(
                        "/api/v1/patients/**",
                        "/api/v1/practitioners/**",
                        "/api/v1/encounters/**",
                        "/api/v1/observations/**",
                        "/api/v1/fhir/**",
                        "/api/v1/bundles/**",
                        "/api/v1/audit-events/**",
                        "/api/v1/terminology/**",
                        "/api/v1/cold-chain/**",
                        "/api/v1/bulk-export/**",
                        "/api/v1/identity-matching/**",
                        "/api/v1/consent/**",
                        "/api/v1/pgx/**",
                        "/api/v1/rpm/**",
                        "/api/v1/subscriptions/**",
                        "/api/v1/bcma/**",
                        "/api/v1/ams/**",
                        "/api/v1/ips/**",
                        "/api/v1/prior-auth/**",
                        "/api/v1/proms/**",
                        "/api/v1/sdoh/**",
                        "/api/v1/certificates/**",
                        "/api/v1/shl/**",
                        "/api/v1/hie/**",
                        "/api/v1/messaging/**",
                        "/api/v1/care-plans/**",
                        "/api/v1/radiology/**",
                        "/api/v1/research/**",
                        "/api/v1/sync/media/**",
                        "/api/v1/clearinghouse/**",
                        "/api/v1/ops/dlq/**",
                        "/api/v1/admin/tenants/**",
                        "/api/v1/ai/**",
                        "/api/v1/cds-services/**",
                        "/cds-services/**"
                )
                .addOperationCustomizer(fhirStandardResponsesAndHeadersCustomizer)
                .build();
    }

    @Bean
    public GroupedOpenApi clinicalAiSuiteGroupedOpenApi(OperationCustomizer fhirStandardResponsesAndHeadersCustomizer) {
        return GroupedOpenApi.builder()
                .group("clinical-ai-suite")
                .displayName("Opes HealthCore Clinical & Operational AI Suite")
                .packagesToScan("com.opeshealth.healthcore.quickstart.web")
                .pathsToMatch("/api/v1/ai/**")
                .addOperationCustomizer(fhirStandardResponsesAndHeadersCustomizer)
                .build();
    }

    @Bean
    public OperationCustomizer fhirStandardResponsesAndHeadersCustomizer() {
        return (Operation operation, HandlerMethod handlerMethod) -> {
            ApiResponses responses = operation.getResponses();
            if (responses == null) {
                responses = new ApiResponses();
                operation.setResponses(responses);
            }

            addDefaultResponseIfAbsent(responses, "400",
                    "Bad Request — HAPI FHIR R4 structural validation failure or malformed clinical payload (returns FHIR OperationOutcome)",
                    "FhirOperationOutcome");
            addDefaultResponseIfAbsent(responses, "401",
                    "Unauthorized — Missing, expired, or invalid OAuth2 / SMART-on-FHIR v2 Bearer JWT",
                    "ApiErrorResponse");
            addDefaultResponseIfAbsent(responses, "403",
                    "Forbidden — Insufficient clinical role, SMART scope, or patient privacy consent policy",
                    "ApiErrorResponse");
            addDefaultResponseIfAbsent(responses, "429",
                    "Too Many Requests — Bucket4j FHIR API rate limit exceeded (returns FHIR OperationOutcome with Retry-After header)",
                    "FhirOperationOutcome");

            ApiResponse rateLimitResponse = responses.get("429");
            if (rateLimitResponse != null) {
                rateLimitResponse.addHeaderObject("Retry-After", new Header()
                        .description("Seconds until the Bucket4j token bucket refills")
                        .schema(new IntegerSchema()));
                rateLimitResponse.addHeaderObject("X-RateLimit-Limit", new Header()
                        .description("Configured requests-per-minute capacity for the current tier")
                        .schema(new IntegerSchema()));
                rateLimitResponse.addHeaderObject("X-RateLimit-Remaining", new Header()
                        .description("Remaining tokens in the current Bucket4j window")
                        .schema(new IntegerSchema()));
            }

            for (Map.Entry<String, ApiResponse> entry : responses.entrySet()) {
                String code = entry.getKey();
                ApiResponse apiResponse = entry.getValue();
                if (apiResponse == null) {
                    continue;
                }
                apiResponse.addHeaderObject("X-Correlation-Id", new Header()
                        .description("Distributed trace correlation identifier recorded in the SHA-256 audit log chain")
                        .schema(new StringSchema()));
                if ("200".equals(code) || "201".equals(code)) {
                    apiResponse.addHeaderObject("ETag", new Header()
                            .description("Weak entity tag (W/\"<version>\") for FHIR optimistic concurrency control via If-Match")
                            .schema(new StringSchema()));
                }
            }
            return operation;
        };
    }

    private static void addDefaultResponseIfAbsent(
            ApiResponses responses,
            String statusCode,
            String description,
            String schemaRefName) {
        if (!responses.containsKey(statusCode)) {
            Schema<?> refSchema = new Schema<>().$ref("#/components/schemas/" + schemaRefName);
            Content content = new Content()
                    .addMediaType(FHIR_JSON_MEDIA_TYPE, new MediaType().schema(refSchema))
                    .addMediaType(JSON_MEDIA_TYPE, new MediaType().schema(refSchema));
            responses.addApiResponse(statusCode, new ApiResponse()
                    .description(description)
                    .content(content));
        }
    }

    private static Schema<?> buildFhirPatientSchema() {
        return new ObjectSchema()
                .description("HL7 FHIR R4 Patient resource representation")
                .addProperty("resourceType", new StringSchema()._default("Patient").description("Fixed value 'Patient'"))
                .addProperty("id", new StringSchema().description("Logical FHIR Patient UUID"))
                .addProperty("active", new BooleanSchema().description("Whether this patient record is in active use"))
                .addProperty("gender", new StringSchema().description("male | female | other | unknown"))
                .addProperty("birthDate", new StringSchema().description("ISO-8601 date of birth (YYYY-MM-DD)"))
                .addProperty("name", new ArraySchema().items(new ObjectSchema().description("FHIR HumanName list")))
                .addProperty("identifier", new ArraySchema().items(new ObjectSchema().description("FHIR Identifier list (MPI & National ID)")))
                .addProperty("telecom", new ArraySchema().items(new ObjectSchema().description("FHIR ContactPoint list (phone, email)")))
                .addProperty("address", new ArraySchema().items(new ObjectSchema().description("FHIR Address list")));
    }

    private static Schema<?> buildFhirPractitionerSchema() {
        return new ObjectSchema()
                .description("HL7 FHIR R4 Practitioner resource representation")
                .addProperty("resourceType", new StringSchema()._default("Practitioner").description("Fixed value 'Practitioner'"))
                .addProperty("id", new StringSchema().description("Logical FHIR Practitioner UUID"))
                .addProperty("active", new BooleanSchema().description("Whether this practitioner record is active"))
                .addProperty("gender", new StringSchema().description("male | female | other | unknown"))
                .addProperty("name", new ArraySchema().items(new ObjectSchema().description("FHIR HumanName with prefix/given/family/suffix")))
                .addProperty("identifier", new ArraySchema().items(new ObjectSchema().description("Medical council license / registry identifiers")))
                .addProperty("qualification", new ArraySchema().items(new ObjectSchema().description("Clinical qualifications and specialties")));
    }

    private static Schema<?> buildFhirEncounterSchema() {
        return new ObjectSchema()
                .description("HL7 FHIR R4 Encounter resource representation")
                .addProperty("resourceType", new StringSchema()._default("Encounter").description("Fixed value 'Encounter'"))
                .addProperty("id", new StringSchema().description("Logical FHIR Encounter UUID"))
                .addProperty("status", new StringSchema().description("planned | arrived | triaged | in-progress | onleave | finished | cancelled | entered-in-error"))
                .addProperty("class", new ObjectSchema().description("FHIR ActEncounterCode (AMB | EMER | IMP | OBSENC | VIRTUAL)"))
                .addProperty("subject", new ObjectSchema().description("Reference to Patient/{id}"))
                .addProperty("participant", new ArraySchema().items(new ObjectSchema().description("Attending Practitioner reference")))
                .addProperty("period", new ObjectSchema().description("Encounter start and end timestamps"))
                .addProperty("reasonCode", new ArraySchema().items(new ObjectSchema().description("Chief complaint / clinical reason")));
    }

    private static Schema<?> buildFhirObservationSchema() {
        return new ObjectSchema()
                .description("HL7 FHIR R4 Observation resource representation")
                .addProperty("resourceType", new StringSchema()._default("Observation").description("Fixed value 'Observation'"))
                .addProperty("id", new StringSchema().description("Logical FHIR Observation UUID"))
                .addProperty("status", new StringSchema().description("registered | preliminary | final | amended | corrected | cancelled | entered-in-error"))
                .addProperty("category", new ArraySchema().items(new ObjectSchema().description("Observation category (vital-signs, laboratory, exam, survey)")))
                .addProperty("code", new ObjectSchema().description("LOINC CodeableConcept"))
                .addProperty("subject", new ObjectSchema().description("Reference to Patient/{id}"))
                .addProperty("encounter", new ObjectSchema().description("Optional reference to Encounter/{id}"))
                .addProperty("effectiveDateTime", new StringSchema().description("ISO-8601 clinical observation timestamp"))
                .addProperty("valueQuantity", new ObjectSchema()
                        .addProperty("value", new NumberSchema())
                        .addProperty("unit", new StringSchema())
                        .addProperty("system", new StringSchema()._default("http://unitsofmeasure.org"))
                        .addProperty("code", new StringSchema()));
    }

    private static Schema<?> buildFhirBundleSchema() {
        return new ObjectSchema()
                .description("HL7 FHIR R4 Bundle resource supporting batch, transaction, batch-response,transaction-response, and searchset")
                .addProperty("resourceType", new StringSchema()._default("Bundle").description("Fixed value 'Bundle'"))
                .addProperty("id", new StringSchema().description("Bundle UUID"))
                .addProperty("type", new StringSchema().description("transaction | batch | transaction-response | batch-response | searchset"))
                .addProperty("total", new IntegerSchema().description("Total matching entries"))
                .addProperty("entry", new ArraySchema().items(new ObjectSchema()
                        .addProperty("fullUrl", new StringSchema().description("Resource URL or urn:uuid:<uuid> placeholder"))
                        .addProperty("resource", new ObjectSchema().description("Embedded FHIR R4 Patient, Practitioner, Encounter, Observation, or OperationOutcome"))
                        .addProperty("request", new ObjectSchema()
                                .addProperty("method", new StringSchema().description("POST | PUT | GET | DELETE"))
                                .addProperty("url", new StringSchema().description("Relative FHIR resource path")))
                        .addProperty("response", new ObjectSchema()
                                .addProperty("status", new StringSchema().description("HTTP status line e.g. '201 Created' or '200 OK'"))
                                .addProperty("location", new StringSchema())
                                .addProperty("etag", new StringSchema()))));
    }

    private static Schema<?> buildFhirAuditEventSchema() {
        return new ObjectSchema()
                .description("HL7 FHIR R4 AuditEvent resource persisted in hc_audit_logs with SHA-256 hash chaining")
                .addProperty("resourceType", new StringSchema()._default("AuditEvent").description("Fixed value 'AuditEvent'"))
                .addProperty("id", new StringSchema().description("AuditEvent UUIDv7 primary key"))
                .addProperty("type", new ObjectSchema().description("AuditEvent type Coding (rest)"))
                .addProperty("subtype", new ArraySchema().items(new ObjectSchema().description("FHIR restful interaction & HIPAA/GDPR action code")))
                .addProperty("action", new StringSchema().description("C | R | U | D | E"))
                .addProperty("recorded", new StringSchema().description("ISO-8601 instant when the FHIR resource access occurred"))
                .addProperty("outcome", new StringSchema().description("0 (Success) | 4 (Minor Failure) | 8 (Serious Failure)"))
                .addProperty("outcomeDesc", new StringSchema().description("PHI-redacted outcome diagnostic message"))
                .addProperty("agent", new ArraySchema().items(new ObjectSchema().description("Authenticated clinician/system actor, role, and network IP")))
                .addProperty("source", new ObjectSchema().description("Facility site key and Opes HealthCore SHA-256 audit observer"))
                .addProperty("entity", new ArraySchema().items(new ObjectSchema().description("Audited FHIR resource reference, Patient subject, sequenceNumber, prevHash, entryHash, and correlationId")));
    }

    private static Schema<?> buildFhirCodeSystemSchema() {
        return new ObjectSchema()
                .description("HL7 FHIR R4 CodeSystem resource representation for LOINC, SNOMED-CT, ICD-10, ICD-11, RxNorm, ATC, and CVX")
                .addProperty("resourceType", new StringSchema()._default("CodeSystem").description("Fixed value 'CodeSystem'"))
                .addProperty("id", new StringSchema().description("Logical CodeSystem short identifier (loinc | snomed-ct | icd-10 | rxnorm | atc | cvx)"))
                .addProperty("url", new StringSchema().description("Canonical HL7 FHIR R4 CodeSystem URI"))
                .addProperty("version", new StringSchema().description("Terminology edition / release version"))
                .addProperty("name", new StringSchema().description("Standard short name"))
                .addProperty("status", new StringSchema()._default("active"))
                .addProperty("count", new IntegerSchema().description("Active concept count"))
                .addProperty("syntaxRule", new StringSchema().description("Code syntax and check-digit validation rule"));
    }

    private static Schema<?> buildFhirOperationOutcomeSchema() {
        return new ObjectSchema()
                .description("HL7 FHIR R4 OperationOutcome returned on validation, rate-limit, or processing failures")
                .addProperty("resourceType", new StringSchema()._default("OperationOutcome").description("Fixed value 'OperationOutcome'"))
                .addProperty("issue", new ArraySchema().items(new ObjectSchema()
                        .addProperty("severity", new StringSchema().description("fatal | error | warning | information"))
                        .addProperty("code", new StringSchema().description("invalid | processing | not-found | conflict | throttled | security"))
                        .addProperty("diagnostics", new StringSchema().description("Human-readable clinical validation or error diagnostic"))));
    }

    private static Schema<?> buildApiErrorResponseSchema() {
        return new ObjectSchema()
                .description("Standard Opes HealthCore structured error envelope with correlation ID")
                .addProperty("errorCode", new StringSchema())
                .addProperty("message", new StringSchema())
                .addProperty("correlationId", new StringSchema())
                .addProperty("occurredAt", new StringSchema())
                .addProperty("details", new ObjectSchema());
    }
}
