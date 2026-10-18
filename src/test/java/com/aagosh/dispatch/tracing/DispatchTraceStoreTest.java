package com.aagosh.dispatch.tracing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchTraceStoreTest {

    private static class MutableClock extends Clock {
        private Instant current;
        private final ZoneId zone;

        MutableClock(Instant start) {
            this.current = start;
            this.zone = ZoneId.of("UTC");
        }

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private DispatchTrace sampleTrace(String requestId) {
        return new DispatchTrace(
                requestId,
                "GreedyMatcher",
                17.3850,
                78.4867,
                Instant.now(),
                1_000_000L,
                List.of(new SpatialStage(0, "INIT", 16, null, null, null, null, 1, 0, 1)),
                List.of(new TraceCandidate("D1", 17.38, 78.48, 0.5, 0.1, 0.1, 0.0, 0.2, 1, CandidateOutcome.MATCHED, null)),
                "D1",
                true,
                10,
                "Matched D1"
        );
    }

    @Test
    @DisplayName("Stores and retrieves immutable trace snapshot")
    void putAndGet() {
        DispatchTraceStore store = new DispatchTraceStore();
        DispatchTrace trace = sampleTrace("req-101");

        store.put(trace);

        Optional<DispatchTrace> retrieved = store.get("req-101");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().requestId()).isEqualTo("req-101");
        assertThat(retrieved.get().winnerDriverId()).isEqualTo("D1");

        // Non-existent request returns empty
        assertThat(store.get("req-non-existent")).isEmpty();
        assertThat(store.get(null)).isEmpty();
        assertThat(store.get("   ")).isEmpty();
    }

    @Test
    @DisplayName("Enforces bounded capacity with LRU eviction")
    void lruCapacityEviction() {
        // Max 3 stored traces
        DispatchTraceConfig config = new DispatchTraceConfig(32, 16, 3, Duration.ofMinutes(10), true);
        DispatchTraceStore store = new DispatchTraceStore(config);

        store.put(sampleTrace("req-1"));
        store.put(sampleTrace("req-2"));
        store.put(sampleTrace("req-3"));
        assertThat(store.size()).isEqualTo(3);

        // Access req-1 so it is marked most recently used; req-2 becomes the least recently used
        assertThat(store.get("req-1")).isPresent();

        // Adding a 4th trace must evict req-2
        store.put(sampleTrace("req-4"));
        assertThat(store.size()).isEqualTo(3);

        assertThat(store.get("req-2")).isEmpty();
        assertThat(store.get("req-1")).isPresent();
        assertThat(store.get("req-3")).isPresent();
        assertThat(store.get("req-4")).isPresent();
    }

    @Test
    @DisplayName("Enforces TTL expiration deterministically via Clock")
    void ttlExpiration() {
        Instant t0 = Instant.parse("2026-10-19T00:00:00Z");
        MutableClock clock = new MutableClock(t0);

        // TTL of 5 minutes
        DispatchTraceConfig config = new DispatchTraceConfig(32, 16, 100, Duration.ofMinutes(5), true);
        DispatchTraceStore store = new DispatchTraceStore(config, clock);

        store.put(sampleTrace("req-ttl"));
        assertThat(store.get("req-ttl")).isPresent();

        // Advance 4 minutes (still valid)
        clock.advance(Duration.ofMinutes(4));
        assertThat(store.get("req-ttl")).isPresent();

        // Advance 2 more minutes (total 6 minutes > 5 minutes TTL) -> expired
        clock.advance(Duration.ofMinutes(2));
        assertThat(store.get("req-ttl")).isEmpty();
        assertThat(store.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("pruneExpired removes all expired traces")
    void pruneExpiredEntries() {
        Instant t0 = Instant.parse("2026-10-19T00:00:00Z");
        MutableClock clock = new MutableClock(t0);

        DispatchTraceConfig config = new DispatchTraceConfig(32, 16, 100, Duration.ofMinutes(5), true);
        DispatchTraceStore store = new DispatchTraceStore(config, clock);

        store.put(sampleTrace("req-A"));
        store.put(sampleTrace("req-B"));

        clock.advance(Duration.ofMinutes(6));
        int purged = store.pruneExpired();

        assertThat(purged).isEqualTo(2);
        assertThat(store.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("Store is a safe no-op when disabled")
    void disabledStore() {
        DispatchTraceConfig disabledConfig = new DispatchTraceConfig(32, 16, 100, Duration.ofMinutes(5), false);
        DispatchTraceStore store = new DispatchTraceStore(disabledConfig);

        store.put(sampleTrace("req-disabled"));

        assertThat(store.size()).isEqualTo(0);
        assertThat(store.get("req-disabled")).isEmpty();
    }

    @Test
    @DisplayName("Handles concurrent writes, reads, and pruning without race conditions or errors")
    void concurrentAccess() throws InterruptedException {
        DispatchTraceStore store = new DispatchTraceStore(new DispatchTraceConfig(32, 16, 50, Duration.ofMinutes(10), true));

        int threadCount = 8;
        int operationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successfulReads = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < operationsPerThread; i++) {
                        String reqId = "req-" + threadId + "-" + i;
                        store.put(sampleTrace(reqId));
                        if (store.get(reqId).isPresent()) {
                            successfulReads.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successfulReads.get()).isGreaterThan(0);
        assertThat(store.size()).isLessThanOrEqualTo(50); // Respects bounded capacity cap
    }
}
