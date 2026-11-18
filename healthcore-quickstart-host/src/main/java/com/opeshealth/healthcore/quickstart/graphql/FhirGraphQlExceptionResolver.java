/*
 * =============================================================================
 * Opes HealthCore SDK — Spring GraphQL FHIR Exception Resolver
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.graphql;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Translates clinical domain, HAPI FHIR R4 validation, optimistic-locking,
 * resource-not-found, and RBAC authorization exceptions into structured
 * GraphQL errors enriched with HL7 FHIR {@code OperationOutcome}-aligned issue metadata.
 */
@Component
public class FhirGraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof NoSuchElementException || ex instanceof EntityNotFoundException) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(ErrorType.NOT_FOUND)
                    .message(ex.getMessage())
                    .extensions(Map.of(
                            "fhirIssueType", "not-found",
                            "errorCode", "RESOURCE_NOT_FOUND",
                            "httpStatus", 404))
                    .build();
        }
        if (ex instanceof IllegalArgumentException) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(ErrorType.BAD_REQUEST)
                    .message(ex.getMessage())
                    .extensions(Map.of(
                            "fhirIssueType", "invalid",
                            "errorCode", "FHIR_VALIDATION_ERROR",
                            "httpStatus", 400))
                    .build();
        }
        if (ex instanceof OptimisticLockingFailureException) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(ErrorType.BAD_REQUEST)
                    .message(ex.getMessage())
                    .extensions(Map.of(
                            "fhirIssueType", "conflict",
                            "errorCode", "OPTIMISTIC_LOCK_CONFLICT",
                            "httpStatus", 412))
                    .build();
        }
        if (ex instanceof IllegalStateException) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(ErrorType.BAD_REQUEST)
                    .message(ex.getMessage())
                    .extensions(Map.of(
                            "fhirIssueType", "duplicate",
                            "errorCode", "DOMAIN_STATE_CONFLICT",
                            "httpStatus", 409))
                    .build();
        }
        if (ex instanceof AccessDeniedException) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(ErrorType.FORBIDDEN)
                    .message("Insufficient FHIR clinical permissions for GraphQL operation")
                    .extensions(Map.of(
                            "fhirIssueType", "forbidden",
                            "errorCode", "RBAC_FORBIDDEN",
                            "httpStatus", 403))
                    .build();
        }
        return null;
    }
}
