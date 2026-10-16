package com.aagosh.dispatch.messaging;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Pure, thread-safe controller that dynamically computes batch collection windows
 * and target batch sizes based on request arrival rate tracking via Exponential Moving Average (EMA).
 *
 * <h2>Little's Law and Control Philosophy</h2>
 * <p>Little's Law states that in any stable queueing system in steady state:
 * <pre>
 *     L = λ * W
 * </pre>
 * where {@code L} is the average queue depth (batch size), {@code λ} is the average arrival
 * rate (requests/sec), and {@code W} is the average waiting time (collection window).
 *
 * <p><strong>Important limitation:</strong> Little's Law is a stationary macroscopic conservation
 * law; it does <em>not</em> by itself specify an optimal closed-loop controller for non-stationary,
 * bursty traffic. Specifically, blindly fixing a target batch size {@code B} yields a required window
 * {@code W = B / λ}, which diverges to infinity as {@code λ → 0} (causing severe starvation under low load),
 * and collapses to zero as {@code λ → ∞}.
 *
 * <p>This controller implements a bounded, dual-constraint control law:
 * <ul>
 *   <li><strong>EMA Arrival Rate:</strong> Smooths instantaneous inter-arrival intervals:
 *       <pre>λ_k = α * λ_instant + (1 - α) * λ_{k-1}</pre></li>
 *   <li><strong>Bounded Target Window:</strong>
 *       <pre>W_target = clamp(nominalBatchTarget / λ_ema, W_min, W_max)</pre></li>
 *   <li><strong>Bounded Target Batch Size:</strong>
 *       <pre>B_target = clamp(ceil(λ_ema * W_target), B_min, B_max)</pre></li>
 *   <li><strong>Low-Traffic Prompt Flush:</strong> When arrival rate is low, the window collapses
 *       to {@code W_min} and target batch size collapses to 1, ensuring zero-latency stalling
 *       for isolated requests.</li>
 *   <li><strong>Immediate Cap Flush:</strong> When {@code currentBatchSize >= B_max}, flushes immediately.</li>
 * </ul>
 */
@Component
public class AdaptiveWindowController {

    private static final long ONE_SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);

    /**
     * Configuration parameters for adaptive windowing.
     *
     * @param alpha                  smoothing factor for EMA arrival rate in range (0.0, 1.0]
     * @param minWindowNanos         minimum window duration (lower bound)
     * @param maxWindowNanos         maximum window duration (upper bound)
     * @param minBatchSize           minimum target batch size (default 1)
     * @param maxBatchSize           hard cap on batch size (e.g. max.poll.records = 50)
     * @param nominalBatchTarget     desired target batch size under sufficient load
     * @param sparseTrafficThreshold arrival rate (req/s) below which traffic is treated as sparse
     */
    public record WindowConfig(
            double alpha,
            long minWindowNanos,
            long maxWindowNanos,
            int minBatchSize,
            int maxBatchSize,
            int nominalBatchTarget,
            double sparseTrafficThreshold
    ) {
        public WindowConfig {
            if (alpha <= 0.0 || alpha > 1.0) {
                throw new IllegalArgumentException("alpha must be in range (0.0, 1.0], was " + alpha);
            }
            if (minWindowNanos <= 0) {
                throw new IllegalArgumentException("minWindowNanos must be > 0, was " + minWindowNanos);
            }
            if (maxWindowNanos < minWindowNanos) {
                throw new IllegalArgumentException("maxWindowNanos (" + maxWindowNanos
                        + ") must be >= minWindowNanos (" + minWindowNanos + ")");
            }
            if (minBatchSize < 1) {
                throw new IllegalArgumentException("minBatchSize must be >= 1, was " + minBatchSize);
            }
            if (maxBatchSize < minBatchSize) {
                throw new IllegalArgumentException("maxBatchSize (" + maxBatchSize
                        + ") must be >= minBatchSize (" + minBatchSize + ")");
            }
            if (nominalBatchTarget < minBatchSize || nominalBatchTarget > maxBatchSize) {
                throw new IllegalArgumentException("nominalBatchTarget (" + nominalBatchTarget
                        + ") must be between minBatchSize and maxBatchSize");
            }
            if (sparseTrafficThreshold < 0.0) {
                throw new IllegalArgumentException("sparseTrafficThreshold must be >= 0.0");
            }
        }

        public static WindowConfig ofMillis(
                double alpha,
                long minWindowMs,
                long maxWindowMs,
                int minBatchSize,
                int maxBatchSize,
                int nominalBatchTarget,
                double sparseTrafficThreshold) {

            return new WindowConfig(
                    alpha,
                    TimeUnit.MILLISECONDS.toNanos(minWindowMs),
                    TimeUnit.MILLISECONDS.toNanos(maxWindowMs),
                    minBatchSize,
                    maxBatchSize,
                    nominalBatchTarget,
                    sparseTrafficThreshold
            );
        }

        public static final WindowConfig DEFAULT = ofMillis(
                0.25,       // alpha
                20L,        // minWindowMs (20ms)
                200L,       // maxWindowMs (200ms)
                1,          // minBatchSize
                50,         // maxBatchSize (matching max.poll.records)
                10,         // nominalBatchTarget
                2.0         // sparseTrafficThreshold (2 req/s)
        );
    }

    /**
     * Reason indicating why a flush decision was made.
     */
    public enum FlushReason {
        /** Hard cap on batch size has been reached or exceeded. */
        MAX_SIZE_REACHED,
        /** Dynamic target batch size has been accumulated. */
        TARGET_SIZE_REACHED,
        /** Target collection window has elapsed. */
        WINDOW_EXPIRED,
        /** Arrival rate is sparse and minimum window has elapsed; flush promptly. */
        LOW_TRAFFIC_PROMPT,
        /** Hold batch: neither batch target nor window duration has elapsed. */
        HOLD
    }

    /**
     * Evaluated decision on whether to flush the current accumulation window.
     *
     * @param shouldFlush           true if the batch should be submitted to matching/persistence
     * @param reason                reason for the flush decision
     * @param remainingWindowNanos  nanoseconds remaining before window expiration (0 if expired)
     * @param remainingBatchCapacity number of records needed to hit target batch size (0 if reached)
     */
    public record FlushDecision(
            boolean shouldFlush,
            FlushReason reason,
            long remainingWindowNanos,
            int remainingBatchCapacity
    ) {
        public static FlushDecision flush(FlushReason reason) {
            return new FlushDecision(true, reason, 0L, 0);
        }

        public static FlushDecision hold(long remainingWindowNanos, int remainingBatchCapacity) {
            return new FlushDecision(false, FlushReason.HOLD, Math.max(0L, remainingWindowNanos), Math.max(0, remainingBatchCapacity));
        }
    }

    private final WindowConfig config;
    private final LongSupplier timeSource;
    private final Object lock = new Object();

    // Dependency-free telemetry counters
    private final AtomicLong totalBatches = new AtomicLong(0);
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicInteger lastBatchSize = new AtomicInteger(0);
    private final AtomicInteger maxObservedBatchSize = new AtomicInteger(0);

    /**
     * Immutable point-in-time snapshot of batch telemetry metrics.
     *
     * @param totalBatches          total batches recorded
     * @param totalRequests         total requests observed across all batches
     * @param lastBatchSize         size of the most recently recorded batch
     * @param maxObservedBatchSize  largest batch size observed since startup or reset
     * @param averageBatchSize      mean batch size across all recorded batches
     * @param estimatedArrivalRate  current estimated request arrival rate (req/s)
     * @param targetWindowNanos     computed Little's Law target collection window
     * @param targetBatchSize       computed target batch size
     */
    public record BatchTelemetrySnapshot(
            long totalBatches,
            long totalRequests,
            int lastBatchSize,
            int maxObservedBatchSize,
            double averageBatchSize,
            double estimatedArrivalRate,
            long targetWindowNanos,
            int targetBatchSize
    ) {}

    // Monotonic state tracking
    private long lastArrivalNanos = -1L;
    private double emaArrivalRate = 0.0; // requests per second
    private boolean initialized = false;

    /**
     * Constructs a controller using the default configuration and system monotonic time source.
     */
    public AdaptiveWindowController() {
        this(WindowConfig.DEFAULT, System::nanoTime);
    }

    /**
     * Constructs a controller using a specified configuration and deterministic time source.
     *
     * @param config     the window and batch sizing parameters
     * @param timeSource monotonic time supplier in nanoseconds
     */
    public AdaptiveWindowController(WindowConfig config, LongSupplier timeSource) {
        this.config = Objects.requireNonNull(config, "config");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
    }

    /**
     * Records the arrival of {@code count} requests at the current monotonic timestamp.
     *
     * @param count the number of newly arrived requests (must be > 0)
     */
    public void recordArrivals(int count) {
        recordArrivals(count, timeSource.getAsLong());
    }

    /**
     * Records the arrival of {@code count} requests at an explicit monotonic timestamp.
     *
     * @param count          the number of newly arrived requests (must be > 0)
     * @param timestampNanos the monotonic timestamp in nanoseconds
     */
    public void recordArrivals(int count, long timestampNanos) {
        if (count <= 0) {
            return;
        }

        totalBatches.incrementAndGet();
        totalRequests.addAndGet(count);
        lastBatchSize.set(count);
        maxObservedBatchSize.accumulateAndGet(count, Math::max);

        synchronized (lock) {
            if (!initialized) {
                // First sample: initialize baseline arrival rate
                lastArrivalNanos = timestampNanos;
                emaArrivalRate = Math.max(1.0, count);
                initialized = true;
                return;
            }

            long elapsedNanos = timestampNanos - lastArrivalNanos;
            // Guard against backward clock jump or identical timestamps in high-concurrency burst
            long safeElapsedNanos = Math.max(1L, elapsedNanos);
            double elapsedSeconds = (double) safeElapsedNanos / ONE_SECOND_NANOS;

            // Instantaneous arrival rate for this interval
            double instantRate = count / elapsedSeconds;

            // Update EMA
            emaArrivalRate = (config.alpha * instantRate) + ((1.0 - config.alpha) * emaArrivalRate);
            lastArrivalNanos = timestampNanos;
        }
    }

    /**
     * Returns the current estimated request arrival rate in requests per second.
     * Applies time-decay if significant idle time has elapsed since the last arrival.
     *
     * @return estimated arrival rate (req/s)
     */
    public double getEstimatedArrivalRate() {
        synchronized (lock) {
            if (!initialized) {
                return 0.0;
            }

            long elapsedSinceLastArrival = Math.max(0L, timeSource.getAsLong() - lastArrivalNanos);
            if (elapsedSinceLastArrival > config.maxWindowNanos()) {
                // Each maxWindow epoch that passes without arrivals counts as a zero-arrival observation.
                // The estimated rate decays geometrically by (1 - alpha) for each elapsed window.
                double elapsedWindows = (double) elapsedSinceLastArrival / config.maxWindowNanos();
                double decayFactor = Math.pow(1.0 - config.alpha(), elapsedWindows);
                return emaArrivalRate * decayFactor;
            }

            return emaArrivalRate;
        }
    }

    /**
     * Computes the adaptive target collection window in nanoseconds.
     *
     * <p>Formula:
     * <pre>
     *     W_target = clamp(nominalBatchTarget / λ_ema, W_min, W_max)
     * </pre>
     * Under low traffic (λ &lt; sparseTrafficThreshold), window collapses to W_min
     * to avoid holding records indefinitely.
     *
     * @return collection window duration in nanoseconds
     */
    public long computeTargetWindowNanos() {
        double rate = getEstimatedArrivalRate();

        if (rate < config.sparseTrafficThreshold()) {
            // Sparse traffic: flush quickly to avoid latency penalties
            return config.minWindowNanos();
        }

        // Little's Law relationship: W = B / λ
        double targetWindowSeconds = config.nominalBatchTarget() / rate;
        long targetWindowNanos = (long) (targetWindowSeconds * ONE_SECOND_NANOS);

        return Math.min(config.maxWindowNanos(), Math.max(config.minWindowNanos(), targetWindowNanos));
    }

    /**
     * Computes the adaptive target batch size.
     *
     * <p>Formula:
     * <pre>
     *     B_target = clamp(ceil(λ_ema * W_target), B_min, B_max)
     * </pre>
     * Under low traffic, target batch size is 1.
     *
     * @return target batch size in records
     */
    public int computeTargetBatchSize() {
        double rate = getEstimatedArrivalRate();

        if (rate < config.sparseTrafficThreshold()) {
            return config.minBatchSize();
        }

        long windowNanos = computeTargetWindowNanos();
        double windowSeconds = (double) windowNanos / ONE_SECOND_NANOS;

        double expected = rate * windowSeconds;
        if (Double.isNaN(expected) || expected <= config.minBatchSize()) {
            return config.minBatchSize();
        }
        if (expected >= config.maxBatchSize()) {
            return config.maxBatchSize();
        }
        return (int) Math.ceil(expected);
    }

    /**
     * Evaluates whether an accumulation window should flush.
     *
     * @param currentBatchSize  the number of records accumulated so far
     * @param windowStartNanos  the monotonic timestamp when accumulation began
     * @return {@link FlushDecision} indicating whether to flush and why
     */
    public FlushDecision evaluateFlush(int currentBatchSize, long windowStartNanos) {
        if (currentBatchSize <= 0) {
            return FlushDecision.hold(config.maxWindowNanos(), config.minBatchSize());
        }

        // 1. Immediate size cap check: hard upper bound
        if (currentBatchSize >= config.maxBatchSize()) {
            return FlushDecision.flush(FlushReason.MAX_SIZE_REACHED);
        }

        long nowNanos = timeSource.getAsLong();
        long elapsedNanos = Math.max(0L, nowNanos - windowStartNanos);

        double rate = getEstimatedArrivalRate();

        // 2. Low traffic prompt flush: flush as soon as minWindow passes
        if (rate < config.sparseTrafficThreshold()) {
            if (elapsedNanos >= config.minWindowNanos()) {
                return FlushDecision.flush(FlushReason.LOW_TRAFFIC_PROMPT);
            }
            long remaining = config.minWindowNanos() - elapsedNanos;
            return FlushDecision.hold(remaining, 0);
        }

        // 3. Dynamic target batch size check
        int targetBatchSize = computeTargetBatchSize();
        if (currentBatchSize >= targetBatchSize) {
            return FlushDecision.flush(FlushReason.TARGET_SIZE_REACHED);
        }

        // 4. Dynamic target window check
        long targetWindowNanos = computeTargetWindowNanos();
        if (elapsedNanos >= targetWindowNanos) {
            return FlushDecision.flush(FlushReason.WINDOW_EXPIRED);
        }

        // 5. Hold: neither condition met
        long remainingNanos = targetWindowNanos - elapsedNanos;
        int remainingRecords = targetBatchSize - currentBatchSize;
        return FlushDecision.hold(remainingNanos, remainingRecords);
    }

    /**
     * Returns an immutable snapshot of current batch telemetry metrics.
     *
     * @return current {@link BatchTelemetrySnapshot}
     */
    public BatchTelemetrySnapshot getTelemetrySnapshot() {
        long batches = totalBatches.get();
        long requests = totalRequests.get();
        int lastSize = lastBatchSize.get();
        int maxSize = maxObservedBatchSize.get();
        double avgSize = batches > 0 ? (double) requests / batches : 0.0;
        double rate = getEstimatedArrivalRate();
        long window = computeTargetWindowNanos();
        int targetBatch = computeTargetBatchSize();

        return new BatchTelemetrySnapshot(
                batches,
                requests,
                lastSize,
                maxSize,
                avgSize,
                rate,
                window,
                targetBatch
        );
    }

    /**
     * Resets the controller state and telemetry counters to uninitialized.
     */
    public void reset() {
        totalBatches.set(0);
        totalRequests.set(0);
        lastBatchSize.set(0);
        maxObservedBatchSize.set(0);
        synchronized (lock) {
            lastArrivalNanos = -1L;
            emaArrivalRate = 0.0;
            initialized = false;
        }
    }

    public WindowConfig getConfig() {
        return config;
    }
}
