/*
 * =============================================================================
 * Opes HealthCore SDK — Data-Driven Workflow Fixture Loader
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.fixture;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and validates standardized JSON workflow fixture datasets from classpath
 * resources, filesystem paths, or inline JSON strings for data-driven testing
 * across all OPES HealthCore modules and industry packs.
 */
public final class DataDrivenWorkflowFixtureLoader {

    public static final String DEFAULT_CLASSPATH_DATASET = "fixtures/healthcore-standard-workflows-dataset.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private DataDrivenWorkflowFixtureLoader() {}

    public static StandardWorkflowFixtureDataset loadDefault() {
        return loadFromClasspath(DEFAULT_CLASSPATH_DATASET);
    }

    public static StandardWorkflowFixtureDataset loadFromClasspath(String resourcePath) {
        String normalized = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        try (InputStream in = DataDrivenWorkflowFixtureLoader.class.getClassLoader().getResourceAsStream(normalized)) {
            if (in == null) {
                throw new IllegalArgumentException("Workflow fixture resource not found on classpath: " + normalized);
            }
            StandardWorkflowFixtureDataset dataset = MAPPER.readValue(in, StandardWorkflowFixtureDataset.class);
            dataset.validate();
            return dataset;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse workflow fixture resource: " + normalized, e);
        }
    }

    public static StandardWorkflowFixtureDataset loadFromPath(Path filePath) {
        try (InputStream in = Files.newInputStream(filePath)) {
            StandardWorkflowFixtureDataset dataset = MAPPER.readValue(in, StandardWorkflowFixtureDataset.class);
            dataset.validate();
            return dataset;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read workflow fixture file: " + filePath, e);
        }
    }

    public static StandardWorkflowFixtureDataset loadFromJson(String json) {
        try {
            StandardWorkflowFixtureDataset dataset = MAPPER.readValue(json, StandardWorkflowFixtureDataset.class);
            dataset.validate();
            return dataset;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse workflow fixture JSON payload", e);
        }
    }
}
