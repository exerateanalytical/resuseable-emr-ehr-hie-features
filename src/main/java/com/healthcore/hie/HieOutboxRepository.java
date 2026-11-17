package com.healthcore.hie;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HieOutboxRepository extends JpaRepository<HieOutboxEventEntity, UUID> {

    @Query(value = """
            SELECT * FROM hie_outbox_events
            WHERE status IN ('PENDING', 'FAILED')
              AND next_attempt_at <= :now
            ORDER BY created_at ASC
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<HieOutboxEventEntity> claimBatchForRelay(@Param("now") Instant now);
}
