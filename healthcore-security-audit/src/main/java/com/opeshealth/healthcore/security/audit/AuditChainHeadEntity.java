/*
 * =============================================================================
 * Opes HealthCore SDK — SHA-256 Hash-Chained Audit Ledger & Chain Head Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.security.audit;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-facility cryptographic chain head row locked briefly during audit append so
 * concurrent clinician requests never fork the SHA-256 hash chain on PG, MSSQL, or MySQL.
 */
@Entity
@Table(name = "hc_audit_chain_heads")
public class AuditChainHeadEntity {

    @Id
    @Column(name = "facility_key", nullable = false, length = 64)
    private String facilityKey;

    @Column(name = "last_hash", nullable = false, length = 64)
    private String lastHash;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    public String getFacilityKey() { return facilityKey; }
    public void setFacilityKey(String facilityKey) { this.facilityKey = facilityKey; }
    public String getLastHash() { return lastHash; }
    public void setLastHash(String lastHash) { this.lastHash = lastHash; }
    public long getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(long sequenceNumber) { this.sequenceNumber = sequenceNumber; }
}
