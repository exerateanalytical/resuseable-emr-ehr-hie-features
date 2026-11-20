package com.opeshealth.healthcore.persistence.fixture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataDrivenWorkflowFixtureLoaderTest {

    @Test
    void loadsAndValidatesStandardizedDefaultDataset() {
        StandardWorkflowFixtureDataset ds = DataDrivenWorkflowFixtureLoader.loadDefault();

        assertEquals("healthcore-standard-workflows-v1", ds.datasetId());
        assertTrue(ds.syntheticOnly());
        assertEquals("SYN-FAC-001", ds.tenant().facilityCode());
        assertEquals(3, ds.patients().size());
        assertEquals(2, ds.encounters().size());
        assertEquals(2, ds.ordersAndResults().orders().size());
        assertEquals(2, ds.pharmacyAndLab().medicationBatches().size());
        assertEquals(2, ds.billingCharges().size());
        assertEquals(1, ds.revenueAndClaims().claims().size());
        assertEquals("SYN-AMOX-500", ds.universalVerticalPacks().get("pharmacyCommerceSku"));
    }

    @Test
    void rejectsNonSyntheticOrReferentiallyBrokenDatasets() {
        String nonSyntheticJson = """
                {
                  "datasetId": "bad-pii",
                  "version": "1.0.0",
                  "syntheticOnly": false,
                  "tenant": { "orgCode": "ORG1", "facilityCode": "FAC1" },
                  "patients": []
                }
                """;
        IllegalArgumentException piiEx = assertThrows(IllegalArgumentException.class,
                () -> DataDrivenWorkflowFixtureLoader.loadFromJson(nonSyntheticJson));
        assertTrue(piiEx.getMessage().contains("syntheticOnly=true"));

        String danglingRefJson = """
                {
                  "datasetId": "bad-ref",
                  "version": "1.0.0",
                  "syntheticOnly": true,
                  "tenant": { "orgCode": "ORG1", "facilityCode": "FAC1" },
                  "patients": [
                    { "mrn": "SYN-1", "familyName": "Test", "givenName": "One", "birthDate": "1990-01-01" }
                  ],
                  "encounters": [
                    { "encounterKey": "ENC-1", "patientMrn": "UNKNOWN-MRN", "encounterClass": "AMB" }
                  ]
                }
                """;
        IllegalArgumentException refEx = assertThrows(IllegalArgumentException.class,
                () -> DataDrivenWorkflowFixtureLoader.loadFromJson(danglingRefJson));
        assertTrue(refEx.getMessage().contains("UNKNOWN-MRN"));
    }
}
