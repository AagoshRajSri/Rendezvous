package com.aagosh.dispatch.tracing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe, bounded in-memory cache for storing and retrieving authentic dispatch execution traces.
 *
 * <h2>Design & Concurrency Guarantees</h2>
 * <ul>
 *   <li><strong>Bounded memory:</strong> Stores at most {@code config.maxStoredTraces()} entries using access-ordered
 *       LRU eviction ({@code removeEldestEntry}). Exceeding capacity deterministically evicts the oldest entry.</li>
 *   <li><strong>TTL Expiration:</strong> Entries older than {@code config.traceTtl()} expire automatically and are
 *       purged on access or during maintenance pruning.</li>
 *   <li><strong>Request-ID Isolation:</strong> Queries are strictly validated to prevent returning data across
 *       mismatched request IDs.</li>
 *   <li><strong>Deterministic testing:</strong> Accepts an optional {@link Clock} instance so TTL behavior can be
 *       tested deterministically without sleeping.</li>
 *   <li><strong>Failure isolation:</strong> Exceptions during store operations are trapped and logged, ensuring
 *       that cache errors cannot fail the dispatch pipeline.</li>
 * </ul>
 */
@Component
public class DispatchTraceStore {

    private static final Logger log = LoggerFactory.getLogger(DispatchTraceStore.class);

    private record TraceEntry(DispatchTrace trace, Instant storedAt) {}

    private final DispatchTraceConfig config;
    private final Clock clock;
    private final Object lock = new Object();

    private final Map<String, TraceEntry> cache;

    public DispatchTraceStore() {
        this(DispatchTraceConfig.DEFAULT, Clock.systemUTC());
    }

    public DispatchTraceStore(DispatchTraceConfig config) {
        this(config, Clock.systemUTC());
    }

    @Autowired
    public DispatchTraceStore(
            @Autowired(required = false) DispatchTraceConfig config,
            @Autowired(required = false) Clock clock
    ) {
        this.config = config != null ? config : DispatchTraceConfig.DEFAULT;
        this.clock = clock != null ? clock : Clock.systemUTC();

        final int maxEntries = this.config.maxStoredTraces();
        this.cache = new LinkedHashMap<>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, TraceEntry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /**
     * Stores a dispatch trace in memory.
     *
     * <p>If tracing is disabled or trace is null, this operation is a safe no-op.
     *
     * @param trace the immutable trace to store; safely ignored if null
     */
    public void put(DispatchTrace trace) {
        if (trace == null || !config.enabled()) {
            return;
        }

        try {
            String requestId = trace.requestId();
            if (requestId == null || requestId.isBlank()) {
                return;
            }

            Instant now = clock.instant();
            synchronized (lock) {
                cache.put(requestId, new TraceEntry(trace, now));
            }
        } catch (Exception e) {
            log.warn("Trace isolation: failed to store trace for request: {}", e.getMessage());
        }
    }

    /**
     * Retrieves an unexpired dispatch trace by request ID.
     *
     * @param requestId the request ID to look up; must not be null or blank
     * @return an {@link Optional} containing the immutable trace if found and unexpired; empty otherwise
     */
    public Optional<DispatchTrace> get(String requestId) {
        if (requestId == null || requestId.isBlank() || !config.enabled()) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        synchronized (lock) {
            TraceEntry entry = cache.get(requestId);
            if (entry == null) {
                return Optional.empty();
            }

            // Verify TTL expiration
            if (isExpired(entry, now)) {
                cache.remove(requestId);
                return Optional.empty();
            }

            // Request ID isolation guard
            if (!requestId.equals(entry.trace.requestId())) {
                log.error("Trace isolation violation: requested key '{}' does not match trace requestId '{}'",
                        requestId, entry.trace.requestId());
                cache.remove(requestId);
                return Optional.empty();
            }

            return Optional.of(entry.trace);
        }
    }

    /**
     * Returns the current number of cached traces (including potentially unpruned expired ones).
     */
    public int size() {
        synchronized (lock) {
            return cache.size();
        }
    }

    /**
     * Returns the current number of valid, unexpired traces.
     */
    public int countUnexpired() {
        Instant now = clock.instant();
        synchronized (lock) {
            int count = 0;
            for (TraceEntry entry : cache.values()) {
                if (!isExpired(entry, now)) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * Actively evicts all expired traces from the cache.
     *
     * @return the number of expired entries purged
     */
    public int pruneExpired() {
        Instant now = clock.instant();
        int purged = 0;
        synchronized (lock) {
            Iterator<Map.Entry<String, TraceEntry>> iterator = cache.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, TraceEntry> entry = iterator.next();
                if (isExpired(entry.getValue(), now)) {
                    iterator.remove();
                    purged++;
                }
            }
        }
        return purged;
    }

    /**
     * Removes all traces from the cache.
     */
    public void clear() {
        synchronized (lock) {
            cache.clear();
        }
    }

    private boolean isExpired(TraceEntry entry, Instant now) {
        return entry.storedAt.plus(config.traceTtl()).isBefore(now);
    }
}
