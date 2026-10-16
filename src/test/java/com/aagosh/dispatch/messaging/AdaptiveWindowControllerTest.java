package com.aagosh.dispatch.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveWindowControllerTest {

    private AtomicLong clockNanos;
    private AdaptiveWindowController.WindowConfig config;
    private AdaptiveWindowController controller;

    @BeforeEach
    void setUp() {
        clockNanos = new AtomicLong(1_000_000_000L); // Start at 1 second
        config = AdaptiveWindowController.WindowConfig.ofMillis(
                0.25,   // alpha
                20L,    // minWindowMs (20ms)
                200L,   // maxWindowMs (200ms)
                1,      // minBatchSize
                50,     // maxBatchSize
                10,     // nominalBatchTarget
                2.0     // sparseTrafficThreshold (2 req/s)
        );
        controller = new AdaptiveWindowController(config, clockNanos::get);
    }

    private void advanceMillis(long ms) {
        clockNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
    }

    private void advanceNanos(long nanos) {
        clockNanos.addAndGet(nanos);
    }

    @Test
    @DisplayName("1. WindowConfig rejects invalid configuration parameters")
    void windowConfigRejectsInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () ->
                AdaptiveWindowController.WindowConfig.ofMillis(0.0, 20, 200, 1, 50, 10, 2.0));
        assertThrows(IllegalArgumentException.class, () ->
                AdaptiveWindowController.WindowConfig.ofMillis(1.5, 20, 200, 1, 50, 10, 2.0));
        assertThrows(IllegalArgumentException.class, () ->
                AdaptiveWindowController.WindowConfig.ofMillis(0.2, 0, 200, 1, 50, 10, 2.0));
        assertThrows(IllegalArgumentException.class, () ->
                AdaptiveWindowController.WindowConfig.ofMillis(0.2, 200, 20, 1, 50, 10, 2.0));
        assertThrows(IllegalArgumentException.class, () ->
                AdaptiveWindowController.WindowConfig.ofMillis(0.2, 20, 200, 0, 50, 10, 2.0));
        assertThrows(IllegalArgumentException.class, () ->
                AdaptiveWindowController.WindowConfig.ofMillis(0.2, 20, 200, 10, 5, 10, 2.0));
        assertThrows(IllegalArgumentException.class, () ->
                AdaptiveWindowController.WindowConfig.ofMillis(0.2, 20, 200, 1, 50, 100, 2.0));
        assertThrows(IllegalArgumentException.class, () ->
                AdaptiveWindowController.WindowConfig.ofMillis(0.2, 20, 200, 1, 50, 10, -1.0));
    }

    @Test
    @DisplayName("2. Uninitialized controller reports 0 arrival rate and defaults to min window")
    void uninitializedControllerState() {
        assertEquals(0.0, controller.getEstimatedArrivalRate());
        assertEquals(config.minWindowNanos(), controller.computeTargetWindowNanos());
        assertEquals(config.minBatchSize(), controller.computeTargetBatchSize());
    }

    @Test
    @DisplayName("3. First arrival sample initializes baseline arrival rate without NaN or division by zero")
    void firstArrivalInitializesStateDeterministically() {
        controller.recordArrivals(1);
        assertTrue(controller.getEstimatedArrivalRate() > 0.0);
        assertFalse(Double.isNaN(controller.getEstimatedArrivalRate()));
        assertFalse(Double.isInfinite(controller.getEstimatedArrivalRate()));
    }

    @Test
    @DisplayName("4. Sparse traffic collapses window to minWindow and prompts flush without waiting")
    void sparseTrafficCollapsesWindowAndFlushesPromptly() {
        // Record 1 arrival
        controller.recordArrivals(1);

        // Advance 2 seconds (0.5 req/s, well below sparseTrafficThreshold = 2.0)
        advanceMillis(2000);
        controller.recordArrivals(1);

        assertTrue(controller.getEstimatedArrivalRate() < config.sparseTrafficThreshold(),
                "Rate should be below sparse threshold");
        assertEquals(config.minWindowNanos(), controller.computeTargetWindowNanos(),
                "Target window should collapse to minWindow");
        assertEquals(config.minBatchSize(), controller.computeTargetBatchSize(),
                "Target batch size should collapse to 1");

        long windowStart = clockNanos.get();

        // After 10ms (< minWindow 20ms), should HOLD
        advanceMillis(10);
        AdaptiveWindowController.FlushDecision d1 = controller.evaluateFlush(1, windowStart);
        assertFalse(d1.shouldFlush());
        assertEquals(AdaptiveWindowController.FlushReason.HOLD, d1.reason());

        // After 20ms (= minWindow), should FLUSH with LOW_TRAFFIC_PROMPT
        advanceMillis(10);
        AdaptiveWindowController.FlushDecision d2 = controller.evaluateFlush(1, windowStart);
        assertTrue(d2.shouldFlush());
        assertEquals(AdaptiveWindowController.FlushReason.LOW_TRAFFIC_PROMPT, d2.reason());
    }

    @Test
    @DisplayName("5. Sustained traffic smoothly updates EMA towards theoretical rate")
    void sustainedTrafficUpdatesEmaSmoothly() {
        controller.recordArrivals(1);

        // Feed 20 arrivals spaced by 20ms each (50 req/s)
        for (int i = 0; i < 20; i++) {
            advanceMillis(20);
            controller.recordArrivals(1);
        }

        double rate = controller.getEstimatedArrivalRate();
        // Should be converging towards 50 req/s
        assertTrue(rate > 35.0 && rate < 55.0, "Expected EMA to converge near 50 req/s, but was " + rate);

        long targetWindowNanos = controller.computeTargetWindowNanos();
        long minNanos = config.minWindowNanos();
        long maxNanos = config.maxWindowNanos();
        assertTrue(targetWindowNanos >= minNanos && targetWindowNanos <= maxNanos,
                "Target window must be bounded by [minWindow, maxWindow]");

        int targetBatchSize = controller.computeTargetBatchSize();
        assertTrue(targetBatchSize >= config.minBatchSize() && targetBatchSize <= config.maxBatchSize(),
                "Target batch size must be bounded by [minBatchSize, maxBatchSize]");
    }

    @Test
    @DisplayName("6. Burst arrivals with delta t = 0 are handled deterministically without divide-by-zero")
    void burstArrivalsWithZeroDeltaTimeHandledSafely() {
        controller.recordArrivals(5);

        // Immediate subsequent arrival at the exact same nanosecond
        controller.recordArrivals(10, clockNanos.get());

        double rate = controller.getEstimatedArrivalRate();
        assertFalse(Double.isNaN(rate));
        assertFalse(Double.isInfinite(rate));
        assertTrue(rate > 0.0);
    }

    @Test
    @DisplayName("7. Maximum batch size limit forces immediate flush regardless of elapsed time")
    void maxSizeLimitForcesImmediateFlush() {
        long windowStart = clockNanos.get();

        // Even with 0 elapsed time, hitting maxBatchSize (50) flushes immediately
        AdaptiveWindowController.FlushDecision decision =
                controller.evaluateFlush(config.maxBatchSize(), windowStart);

        assertTrue(decision.shouldFlush());
        assertEquals(AdaptiveWindowController.FlushReason.MAX_SIZE_REACHED, decision.reason());
        assertEquals(0L, decision.remainingWindowNanos());
        assertEquals(0, decision.remainingBatchCapacity());
    }

    @Test
    @DisplayName("8. Target batch size triggers flush when accumulated count satisfies target")
    void targetBatchSizeTriggersFlush() {
        // Establish high traffic so targetBatchSize > 1
        controller.recordArrivals(1);
        for (int i = 0; i < 10; i++) {
            advanceMillis(10); // 100 req/s
            controller.recordArrivals(1);
        }

        int targetBatch = controller.computeTargetBatchSize();
        assertTrue(targetBatch > 1);

        long windowStart = clockNanos.get();
        // Have fewer records than target: HOLD
        AdaptiveWindowController.FlushDecision d1 =
                controller.evaluateFlush(targetBatch - 1, windowStart);
        assertFalse(d1.shouldFlush());

        // Reach target batch size: FLUSH with TARGET_SIZE_REACHED
        AdaptiveWindowController.FlushDecision d2 =
                controller.evaluateFlush(targetBatch, windowStart);
        assertTrue(d2.shouldFlush());
        assertEquals(AdaptiveWindowController.FlushReason.TARGET_SIZE_REACHED, d2.reason());
    }

    @Test
    @DisplayName("9. Window expiration triggers flush when elapsed time exceeds target window")
    void windowExpirationTriggersFlush() {
        // Establish moderate traffic
        controller.recordArrivals(1);
        for (int i = 0; i < 10; i++) {
            advanceMillis(50); // 20 req/s
            controller.recordArrivals(1);
        }

        long targetWindowNanos = controller.computeTargetWindowNanos();
        long windowStart = clockNanos.get();

        // 5ms before window expiration: HOLD
        advanceNanos(targetWindowNanos - TimeUnit.MILLISECONDS.toNanos(5));
        AdaptiveWindowController.FlushDecision d1 = controller.evaluateFlush(2, windowStart);
        assertFalse(d1.shouldFlush());

        // At or after window expiration: FLUSH with WINDOW_EXPIRED
        advanceMillis(10);
        AdaptiveWindowController.FlushDecision d2 = controller.evaluateFlush(2, windowStart);
        assertTrue(d2.shouldFlush());
        assertEquals(AdaptiveWindowController.FlushReason.WINDOW_EXPIRED, d2.reason());
    }

    @Test
    @DisplayName("10. Idle time decays arrival rate towards zero")
    void idleTimeDecaysArrivalRateTowardsZero() {
        controller.recordArrivals(1);
        for (int i = 0; i < 10; i++) {
            advanceMillis(10); // high traffic
            controller.recordArrivals(1);
        }

        double activeRate = controller.getEstimatedArrivalRate();
        assertTrue(activeRate > 10.0);

        // Advance by 5 seconds of silence (> maxWindowNanos = 200ms)
        advanceMillis(5000);

        double decayedRate = controller.getEstimatedArrivalRate();
        assertTrue(decayedRate < activeRate, "Rate should decay during silence");
        assertTrue(decayedRate < config.sparseTrafficThreshold(),
                "Rate should decay below sparse threshold after 5 seconds of silence");
    }

    @Test
    @DisplayName("11. Reset restores controller to clean uninitialized state")
    void resetRestoresUninitializedState() {
        controller.recordArrivals(10);
        assertTrue(controller.getEstimatedArrivalRate() > 0.0);

        controller.reset();

        assertEquals(0.0, controller.getEstimatedArrivalRate());
        assertEquals(config.minWindowNanos(), controller.computeTargetWindowNanos());
        assertEquals(config.minBatchSize(), controller.computeTargetBatchSize());
    }

    @Test
    @DisplayName("12. Zero or negative arrival counts are safely ignored without altering state")
    void zeroOrNegativeCountsSafelyIgnored() {
        controller.recordArrivals(0);
        assertEquals(0.0, controller.getEstimatedArrivalRate());

        controller.recordArrivals(-5);
        assertEquals(0.0, controller.getEstimatedArrivalRate());
    }

    @Test
    @DisplayName("13. Telemetry snapshot tracks total batches, requests, average batch size, and bounds")
    void telemetrySnapshotTracksMetricsAccurately() {
        AdaptiveWindowController.BatchTelemetrySnapshot initial = controller.getTelemetrySnapshot();
        assertEquals(0, initial.totalBatches());
        assertEquals(0, initial.totalRequests());
        assertEquals(0, initial.lastBatchSize());
        assertEquals(0, initial.maxObservedBatchSize());
        assertEquals(0.0, initial.averageBatchSize());

        // Batch 1: 5 requests
        controller.recordArrivals(5);
        // Batch 2: 15 requests
        advanceMillis(20);
        controller.recordArrivals(15);
        // Batch 3: 10 requests
        advanceMillis(20);
        controller.recordArrivals(10);

        AdaptiveWindowController.BatchTelemetrySnapshot snapshot = controller.getTelemetrySnapshot();
        assertEquals(3, snapshot.totalBatches());
        assertEquals(30, snapshot.totalRequests());
        assertEquals(10, snapshot.lastBatchSize());
        assertEquals(15, snapshot.maxObservedBatchSize());
        assertEquals(10.0, snapshot.averageBatchSize(), 1e-6);
        assertTrue(snapshot.estimatedArrivalRate() > 0.0);
        assertTrue(snapshot.targetWindowNanos() >= config.minWindowNanos()
                && snapshot.targetWindowNanos() <= config.maxWindowNanos());
        assertTrue(snapshot.targetBatchSize() >= config.minBatchSize()
                && snapshot.targetBatchSize() <= config.maxBatchSize());
    }

    @Test
    @DisplayName("14. Reset clears telemetry counters in addition to arrival rate state")
    void resetClearsTelemetryMetrics() {
        controller.recordArrivals(10);
        advanceMillis(20);
        controller.recordArrivals(20);

        assertEquals(2, controller.getTelemetrySnapshot().totalBatches());
        assertEquals(30, controller.getTelemetrySnapshot().totalRequests());

        controller.reset();

        AdaptiveWindowController.BatchTelemetrySnapshot cleared = controller.getTelemetrySnapshot();
        assertEquals(0, cleared.totalBatches());
        assertEquals(0, cleared.totalRequests());
        assertEquals(0, cleared.lastBatchSize());
        assertEquals(0, cleared.maxObservedBatchSize());
        assertEquals(0.0, cleared.averageBatchSize());
        assertEquals(0.0, cleared.estimatedArrivalRate());
    }

    @Test
    @DisplayName("15. Extreme burst arrival rates clamp safely to maxBatchSize and minWindow without overflow")
    void extremeBurstArrivalRatesClampSafely() {
        controller.recordArrivals(50);
        // Advance only 1 nanosecond (arrival rate 50 / 1e-9 = 50 billion req/s)
        advanceNanos(1);
        controller.recordArrivals(50);

        long window = controller.computeTargetWindowNanos();
        int batch = controller.computeTargetBatchSize();

        assertEquals(config.minWindowNanos(), window, "Extreme rate must clamp to minWindow");
        assertEquals(config.maxBatchSize(), batch, "Extreme rate must clamp to maxBatchSize");
    }
}
