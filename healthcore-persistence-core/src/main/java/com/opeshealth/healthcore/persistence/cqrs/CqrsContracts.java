/*
 * =============================================================================
 * Opes HealthCore SDK — CQRS Command/Query Bus & Keyset Pagination Contracts
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.cqrs;

import java.util.List;
import java.util.UUID;

/**
 * Standardized CQRS abstractions and keyset pagination types shared across all
 * Opes HealthCore starters.
 *
 * <p>Separating mutating {@link Command} handlers (which enforce DDD aggregate invariants
 * and emit Transactional Outbox events) from {@link Query} handlers (which read optimized
 * projections inside read-only transactions) keeps clinical dashboards sub-10ms.</p>
 */
public final class CqrsContracts {

    private CqrsContracts() {}

    /** Marker interface for a write-side CQRS command returning result {@code R}. */
    public interface Command<R> {}

    /** Handler executing exactly one write-side {@link Command}. */
    @FunctionalInterface
    public interface CommandHandler<C extends Command<R>, R> {
        R handle(C command);
    }

    /** Marker interface for a read-side CQRS query returning projection {@code R}. */
    public interface Query<R> {}

    /** Handler executing exactly one read-side {@link Query} inside a read-only transaction. */
    @FunctionalInterface
    public interface QueryHandler<Q extends Query<R>, R> {
        R handle(Q query);
    }

    /**
     * Keyset (cursor) page envelope replacing slow SQL {@code OFFSET} scans on
     * multi-million-row patient, encounter, and ledger tables across PG, MSSQL, and MySQL.
     *
     * @param items      current page slice
     * @param nextCursor opaque UUIDv7 cursor of the last item, or {@code null} if exhausted
     * @param hasMore    whether additional records follow {@code nextCursor}
     */
    public record CursorPage<T>(List<T> items, UUID nextCursor, boolean hasMore) {
        public static <T> CursorPage<T> of(List<T> fetchedPlusOne, int requestedSize, java.util.function.Function<T, UUID> idExtractor) {
            boolean more = fetchedPlusOne.size() > requestedSize;
            List<T> slice = more ? fetchedPlusOne.subList(0, requestedSize) : fetchedPlusOne;
            UUID cursor = slice.isEmpty() ? null : idExtractor.apply(slice.getLast());
            return new CursorPage<>(slice, more ? cursor : null, more);
        }
    }
}
