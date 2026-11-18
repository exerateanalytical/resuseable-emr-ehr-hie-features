/*
 * =============================================================================
 * Opes HealthCore SDK — Global @ControllerAdvice Exception Handler (JSON + FHIR)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Global {@link ControllerAdvice} converting Spring Boot, JPA, Security, Validation,
 * and domain exceptions into either:
 * <ul>
 *   <li>HL7 FHIR R4 {@link OperationOutcome} JSON ({@code application/fhir+json}) for
 *       {@code /fhir/**} paths or {@code Accept: application/fhir+json} clients</li>
 *   <li>Standardized {@link ApiErrorResponse} JSON ({@code application/json}) for
 *       HealthCore REST API endpoints</li>
 * </ul>
 */
@ControllerAdvice
@ResponseBody
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HealthCoreApiExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(HealthCoreApiExceptionAdvice.class);
    private static final FhirContext FHIR_R4 = FhirContext.forR4();
    private static final MediaType FHIR_JSON_MEDIA_TYPE = MediaType.parseMediaType("application/fhir+json");

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldValidationError(
            String field,
            String message
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiErrorResponse(
            Instant timestamp,
            int status,
            String error,
            String code,
            String message,
            String path,
            String correlationId,
            List<FieldValidationError> fieldErrors
    ) {}

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidationErrors(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<FieldValidationError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldValidationError(fe.getField(), safeFieldMessage(fe)))
                .toList();
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                IssueType.INVALID,
                "Request validation failed for " + fields.size() + " field(s)",
                fields,
                req);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<Object> handleBadRequest(Exception ex, HttpServletRequest req) {
        String code = ex instanceof HttpMessageNotReadableException ? "MALFORMED_REQUEST_BODY" : "INVALID_ARGUMENT";
        IssueType issueType = ex instanceof HttpMessageNotReadableException ? IssueType.STRUCTURE : IssueType.INVALID;
        return buildResponse(HttpStatus.BAD_REQUEST, code, issueType, safeMessage(ex, "Malformed or invalid request"), null, req);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleUnauthorized(AuthenticationException ex, HttpServletRequest req) {
        return buildResponse(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", IssueType.LOGIN, "Authentication is required", null, req);
    }

    @ExceptionHandler({AccessDeniedException.class, SecurityException.class})
    public ResponseEntity<Object> handleForbidden(Exception ex, HttpServletRequest req) {
        return buildResponse(HttpStatus.FORBIDDEN, "ACCESS_DENIED", IssueType.FORBIDDEN, safeMessage(ex, "Access denied"), null, req);
    }

    @ExceptionHandler({NoSuchElementException.class, EntityNotFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Object> handleNotFound(Exception ex, HttpServletRequest req) {
        return buildResponse(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", IssueType.NOTFOUND, safeMessage(ex, "Requested resource not found"), null, req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return buildResponse(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", IssueType.NOTSUPPORTED, safeMessage(ex, "HTTP method not supported"), null, req);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Object> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
        return buildResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", IssueType.NOTSUPPORTED, safeMessage(ex, "Unsupported media type"), null, req);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest req) {
        return buildResponse(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT", IssueType.CONFLICT,
                "Concurrent modification detected; refresh resource ETag/version and retry", null, req);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        return buildResponse(HttpStatus.CONFLICT, "DATA_INTEGRITY_CONFLICT", IssueType.DUPLICATE,
                "Operation violates uniqueness or referential integrity constraint", null, req);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Object> handleIllegalState(IllegalStateException ex, HttpServletRequest req) {
        return buildResponse(HttpStatus.CONFLICT, "BUSINESS_RULE_VIOLATION", IssueType.BUSINESSRULE,
                safeMessage(ex, "Operation violates clinical or workflow state rule"), null, req);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Object> handleResponseStatus(ResponseStatusException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        HttpStatus resolved = status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
        IssueType issueType = switch (resolved.value()) {
            case 401 -> IssueType.LOGIN;
            case 403 -> IssueType.FORBIDDEN;
            case 404 -> IssueType.NOTFOUND;
            case 409 -> IssueType.CONFLICT;
            default -> resolved.is4xxClientError() ? IssueType.INVALID : IssueType.EXCEPTION;
        };
        return buildResponse(resolved, "HTTP_" + resolved.value(), issueType,
                ex.getReason() != null ? ex.getReason() : resolved.getReasonPhrase(), null, req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnhandled(Exception ex, HttpServletRequest req) {
        String correlationId = resolveCorrelationId(req);
        log.error("Unhandled HealthCore API exception correlationId={} path={}",
                correlationId, req != null ? req.getRequestURI() : "unknown", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", IssueType.EXCEPTION,
                "Unexpected internal error occurred; reference correlationId " + correlationId, null, req);
    }

    public ResponseEntity<Object> buildResponse(
            HttpStatus status,
            String errorCode,
            IssueType fhirIssueType,
            String message,
            List<FieldValidationError> fieldErrors,
            HttpServletRequest req) {
        String path = req != null ? req.getRequestURI() : "";
        String correlationId = resolveCorrelationId(req);

        if (isFhirRequest(req, path)) {
            OperationOutcome outcome = new OperationOutcome();
            outcome.setId("oo-" + correlationId);
            if (fieldErrors != null && !fieldErrors.isEmpty()) {
                for (FieldValidationError fe : fieldErrors) {
                    OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue()
                            .setSeverity(IssueSeverity.ERROR)
                            .setCode(fhirIssueType)
                            .setDiagnostics(fe.field() + ": " + fe.message());
                    issue.getDetails().addCoding()
                            .setSystem("https://www.opeshealthsystems.com/fhir/CodeSystem/api-error")
                            .setCode(errorCode);
                    issue.addExpression(fe.field());
                }
            } else {
                OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue()
                        .setSeverity(status.is5xxServerError() ? IssueSeverity.FATAL : IssueSeverity.ERROR)
                        .setCode(fhirIssueType)
                        .setDiagnostics(message);
                issue.getDetails().addCoding()
                        .setSystem("https://www.opeshealthsystems.com/fhir/CodeSystem/api-error")
                        .setCode(errorCode);
            }
            String json = FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome);
            return ResponseEntity.status(status)
                    .contentType(FHIR_JSON_MEDIA_TYPE)
                    .header("X-Correlation-Id", correlationId)
                    .body(json);
        }

        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                errorCode,
                message,
                path,
                correlationId,
                fieldErrors
        );
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Correlation-Id", correlationId)
                .body(body);
    }

    private static boolean isFhirRequest(HttpServletRequest req, String path) {
        if (path != null && (path.startsWith("/fhir/") || path.equals("/fhir"))) {
            return true;
        }
        if (req == null) {
            return false;
        }
        String accept = req.getHeader("Accept");
        return accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains("application/fhir+json");
    }

    private static String resolveCorrelationId(HttpServletRequest req) {
        String mdc = MDC.get("correlationId");
        if (mdc != null && !mdc.isBlank()) {
            return mdc;
        }
        if (req != null) {
            String header = req.getHeader("X-Correlation-Id");
            if (header != null && !header.isBlank()) {
                return header;
            }
        }
        return UUID.randomUUID().toString();
    }

    private static String safeFieldMessage(FieldError fe) {
        return fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value";
    }

    private static String safeMessage(Exception ex, String fallback) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            return fallback;
        }
        return msg.length() > 300 ? msg.substring(0, 300) : msg;
    }
}
