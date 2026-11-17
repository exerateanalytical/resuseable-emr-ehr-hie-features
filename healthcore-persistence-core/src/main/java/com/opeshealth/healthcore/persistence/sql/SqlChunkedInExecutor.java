/*
 * =============================================================================
 * Opes HealthCore SDK — SQL Server Safe Chunked IN-Clause Executor
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.sql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Splits large {@code IN (...)} parameter collections into safe chunks of at most 1,000 items.
 *
 * <p><b>Why this is critical:</b> Windows SQL Server (MSSQL) JDBC drivers throw
 * {@code SQLServerException: The incoming request has too many parameters (max 2100)}
 * when a bulk EHR query passes thousands of patient or order IDs in a single {@code IN} clause.</p>
 */
public final class SqlChunkedInExecutor {

    /** Conservative cap well below MSSQL's 2,100 parameter limit and Oracle's 1,000 limit. */
    public static final int SAFE_CHUNK_SIZE = 1000;

    private SqlChunkedInExecutor() {}

    /**
     * Executes {@code queryFn} in sequential chunks of up to 1,000 elements and
     * aggregates all returned rows into a single immutable-ready list.
     *
     * @param ids     full collection of filter IDs
     * @param queryFn repository query function accepting a single chunk
     * @return merged results across all chunks
     */
    public static <I, R> List<R> executeChunked(Collection<I> ids, Function<List<I>, List<R>> queryFn) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<I> all = ids instanceof List<I> list ? list : new ArrayList<>(ids);
        if (all.size() <= SAFE_CHUNK_SIZE) {
            return queryFn.apply(all);
        }
        List<R> aggregated = new ArrayList<>(all.size());
        for (int start = 0; start < all.size(); start += SAFE_CHUNK_SIZE) {
            int end = Math.min(start + SAFE_CHUNK_SIZE, all.size());
            aggregated.addAll(queryFn.apply(all.subList(start, end)));
        }
        return aggregated;
    }
}
