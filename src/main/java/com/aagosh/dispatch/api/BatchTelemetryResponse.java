package com.aagosh.dispatch.api;

import com.aagosh.dispatch.messaging.AdaptiveWindowController.BatchTelemetrySnapshot;

import java.util.Objects;

/**
 * Immutable API response DTO for Kafka batch-consumption and arrival-rate telemetry.
 *
 * <p>Exposes a read-only point-in-time snapshot of the internal metrics collected by
 * {@link com.aagosh.dispatch.messaging.AdaptiveWindowController} during native batch consumption.
 * Used by load-testing runners (such as k6) to evaluate observed batch sizes and Little's Law
 * parameters without mutating controller state.
 *
 * @param totalBatches          total batches recorded since startup or reset
 * @param totalRequests         total ride requests observed across all batches
 * @param lastBatchSize         size of the most recently recorded batch
 * @param maxObservedBatchSize  peak batch size observed since startup or reset
 * @param averageBatchSize      cumulative mean batch size (totalRequests / totalBatches)
 * @param estimatedArrivalRate  exponential moving average arrival rate (requests/sec)
 * @param targetWindowNanos     computed Little's Law target collection window in nanoseconds
 * @param targetBatchSize       computed target batch size
 */
public record BatchTelemetryResponse(
        long totalBatches,
        long totalRequests,
        int lastBatchSize,
        int maxObservedBatchSize,
        double averageBatchSize,
        double estimatedArrivalRate,
        long targetWindowNanos,
        int targetBatchSize
) {
    /**
     * Constructs a response DTO from an internal controller snapshot.
     *
     * @param snapshot the internal telemetry snapshot; must not be null
     * @return an immutable {@link BatchTelemetryResponse}
     */
    public static BatchTelemetryResponse from(BatchTelemetrySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new BatchTelemetryResponse(
                snapshot.totalBatches(),
                snapshot.totalRequests(),
                snapshot.lastBatchSize(),
                snapshot.maxObservedBatchSize(),
                snapshot.averageBatchSize(),
                snapshot.estimatedArrivalRate(),
                snapshot.targetWindowNanos(),
                snapshot.targetBatchSize()
        );
    }
}
