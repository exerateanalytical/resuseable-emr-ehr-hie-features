/*
 * =============================================================================
 * Opes HealthCore SDK — Composable Module Slice Spring @Condition Helper
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.autoconfigure;

import org.springframework.context.annotation.ConditionContext;
import org.springframework.util.ClassUtils;

import java.util.List;

/**
 * Evaluates whether a fine-grained clinical/operational module slice should activate:
 * <ol>
 *   <li>Explicit configuration property {@code healthcore.modules.<slice>.enabled} always wins.</li>
 *   <li>If one or more fine-grained starters in the domain group are present on the classpath,
 *       only slices whose marker class is present are activated.</li>
 *   <li>If the developer depended directly on the composite starter without fine-grained markers,
 *       all slices in that group activate automatically for zero-config hospital/EHR builds.</li>
 * </ol>
 */
public final class ModuleSliceConditionSupport {

    private ModuleSliceConditionSupport() {}

    public static boolean isSliceEnabled(
            ConditionContext context,
            String propertyKey,
            String targetMarkerClassName,
            List<String> groupMarkerClassNames) {
        String explicit = context.getEnvironment().getProperty(propertyKey);
        if (explicit != null && !explicit.isBlank()) {
            return Boolean.parseBoolean(explicit.trim());
        }
        ClassLoader cl = context.getClassLoader();
        boolean anyFineGrainedMarkerPresent = groupMarkerClassNames.stream()
                .anyMatch(cls -> ClassUtils.isPresent(cls, cl));
        if (!anyFineGrainedMarkerPresent) {
            return true;
        }
        return ClassUtils.isPresent(targetMarkerClassName, cl);
    }
}
