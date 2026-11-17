package com.healthcore.api;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = FhirPatientController.class)
public class FhirExceptionHandler {

    private static final MediaType FHIR_JSON = MediaType.parseMediaType(FhirPatientController.FHIR_JSON_VALUE);
    private final FhirContext fhirContext = FhirContext.forR4();

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> handleNotFound(NoSuchElementException ex) {
        return buildOutcome(HttpStatus.NOT_FOUND, OperationOutcome.IssueType.NOTFOUND, ex.getMessage());
    }

    @ExceptionHandler({DataFormatException.class, IllegalArgumentException.class})
    public ResponseEntity<String> handleInvalidFhir(RuntimeException ex) {
        return buildOutcome(HttpStatus.UNPROCESSABLE_ENTITY, OperationOutcome.IssueType.STRUCTURE, ex.getMessage());
    }

    private ResponseEntity<String> buildOutcome(HttpStatus status, OperationOutcome.IssueType issueType, String diagnostics) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(issueType)
                .setDiagnostics(diagnostics);
        String json = fhirContext.newJsonParser().encodeResourceToString(outcome);
        return ResponseEntity.status(status).contentType(FHIR_JSON).body(json);
    }
}
