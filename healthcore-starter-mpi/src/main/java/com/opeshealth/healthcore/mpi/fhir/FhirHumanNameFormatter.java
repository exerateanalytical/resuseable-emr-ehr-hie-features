package com.opeshealth.healthcore.mpi.fhir;

import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.StringType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * FHIR R4 HumanName formatter. Avoids legacy HAPI helper methods that are
 * unavailable in the versioned R4 model, and preserves structured name parts.
 */
public final class FhirHumanNameFormatter {
    private FhirHumanNameFormatter() {}

    public static String prefixes(HumanName name) {
        return name == null ? "" : parts(name.getPrefix());
    }

    public static String display(HumanName name) {
        if (name == null) return "";
        if (name.hasText() && name.getText() != null && !name.getText().isBlank()) {
            return name.getText().trim();
        }
        List<String> components = new ArrayList<>();
        String prefix = prefixes(name);
        if (!prefix.isEmpty()) components.add(prefix);
        String given = parts(name.getGiven());
        if (!given.isEmpty()) components.add(given);
        if (name.hasFamily() && !name.getFamily().isBlank()) {
            components.add(name.getFamily().trim());
        }
        String suffix = parts(name.getSuffix());
        if (!suffix.isEmpty()) components.add(suffix);
        return String.join(" ", components);
    }

    private static String parts(List<StringType> values) {
        if (values == null) return "";
        return values.stream()
                .filter(Objects::nonNull)
                .map(StringType::getValue)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .collect(Collectors.joining(" "));
    }
}
