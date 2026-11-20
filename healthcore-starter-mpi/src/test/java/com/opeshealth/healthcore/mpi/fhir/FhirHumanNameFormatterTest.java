package com.opeshealth.healthcore.mpi.fhir;

import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FhirHumanNameFormatterTest {

    @Test
    void rendersStructuredNameWithoutLegacyHapiMethods() {
        HumanName name = new HumanName();
        name.addPrefix("Dr");
        name.addPrefix("Mme");
        name.addGiven("Alice");
        name.addGiven("Marie");
        name.setFamily("Nshome");
        name.addSuffix("Jr");
        assertEquals("Dr Mme", FhirHumanNameFormatter.prefixes(name));
        assertEquals("Dr Mme Alice Marie Nshome Jr", FhirHumanNameFormatter.display(name));
    }

    @Test
    void prefersDeclaredTextAndHandlesEmptyNames() {
        HumanName name = new HumanName().setText("  Preferred Display  ");
        assertEquals("Preferred Display", FhirHumanNameFormatter.display(name));
        assertEquals("", FhirHumanNameFormatter.display(null));
        assertEquals("", FhirHumanNameFormatter.prefixes(null));
    }
}
