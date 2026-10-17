/**
 * k6/common.js
 *
 * Shared utilities, metric definitions, and lifecycle helpers for Rendezvous k6 benchmarks.
 *
 * <h2>Measurement Strategy</h2>
 * <ul>
 *   <li><strong>Ingestion Latency:</strong> Measures client-to-broker roundtrip of {@code POST /request}.
 *       Returns HTTP 202 once Kafka broker acknowledges the write.</li>
 *   <li><strong>Dispatch Latency:</strong> Measures end-to-end duration from request submission until
 *       a terminal state ({@code MATCHED} or {@code NO_DRIVERS}) is durably committed in PostgreSQL.</li>
 *   <li><strong>Bounded Polling Overhead:</strong> Only a sampled subset of requests (e.g. 5-10%)
 *       is tracked via {@code GET /api/requests/{id}} to avoid overwhelming the database connection pool.</li>
 *   <li><strong>Telemetry Snapshot Deltas:</strong> Queries {@code GET /api/telemetry/batches} before
 *       and after the test run to determine the exact batching characteristics observed by the consumer.</li>
 * </ul>
 */

import http from 'k6/http';
import { sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

// =============================================================================
// Custom Metrics
// =============================================================================

// Latency trends
export const ingestionLatency = new Trend('ingestion_latency', true);
export const dispatchLatency = new Trend('dispatch_latency', true);
export const observationDelay = new Trend('observation_delay', true);
export const pollAttempts = new Trend('poll_attempts', false);

// Outcome counters
export const dispatchSuccessMatched = new Counter('dispatch_success_matched');
export const dispatchSuccessNoDrivers = new Counter('dispatch_success_no_drivers');
export const dispatchTimeout = new Counter('dispatch_timeout');
export const dispatchFailure = new Counter('dispatch_failure');
export const pollRequestCount = new Counter('poll_request_count');
export const sampledRequests = new Counter('sampled_requests');

// =============================================================================
// Helper Functions
// =============================================================================

export function getBaseUrl() {
    return __ENV.DISPATCH_BASE_URL || 'http://localhost:8080';
}

/**
 * Generates a collision-free monotonic request ID.
 */
export function generateRequestId(prefix = 'req') {
    const vu = typeof __VU !== 'undefined' ? __VU : 0;
    const iter = typeof __ITER !== 'undefined' ? __ITER : 0;
    const rand = Math.floor(Math.random() * 100000);
    return `k6-${prefix}-v${vu}-i${iter}-${Date.now()}-${rand}`;
}

/**
 * Generates valid RideRequestDto payload with bounded jitter around Hyderabad center.
 */
export function generateRidePayload(requestId) {
    const baseLat = 17.3850;
    const baseLon = 78.4867;
    // Bounded spatial spread within ~3 km radius
    const latJitter = (Math.random() - 0.5) * 0.03;
    const lonJitter = (Math.random() - 0.5) * 0.03;

    return JSON.stringify({
        requestId: requestId,
        latitude: parseFloat((baseLat + latJitter).toFixed(6)),
        longitude: parseFloat((baseLon + lonJitter).toFixed(6)),
    });
}

/**
 * Queries the read-only batch telemetry endpoint.
 */
export function fetchBatchTelemetry(baseUrl) {
    const url = `${baseUrl}/api/telemetry/batches`;
    const res = http.get(url, {
        headers: { Accept: 'application/json' },
        tags: { type: 'telemetry' },
        timeout: '5s',
    });

    if (res.status === 200) {
        try {
            return JSON.parse(res.body);
        } catch (e) {
            console.error(`[telemetry] Failed to parse JSON response: ${e.message}`);
            return null;
        }
    } else {
        console.error(`[telemetry] GET ${url} returned HTTP ${res.status}: ${res.body}`);
        return null;
    }
}

/**
 * Calculates point-in-time delta between pre-run and post-run telemetry snapshots.
 */
export function calculateBatchDelta(pre, post) {
    if (!pre || !post) {
        return {
            valid: false,
            reason: 'Missing pre-run or post-run telemetry snapshot',
        };
    }

    const deltaBatches = post.totalBatches - pre.totalBatches;
    const deltaRequests = post.totalRequests - pre.totalRequests;

    if (deltaBatches < 0 || deltaRequests < 0) {
        return {
            valid: false,
            reason: 'Counter reset or divergence detected during run',
            deltaBatches,
            deltaRequests,
        };
    }

    const observedAverageBatchSize = deltaBatches > 0
        ? parseFloat((deltaRequests / deltaBatches).toFixed(2))
        : 0.0;

    return {
        valid: true,
        deltaBatches,
        deltaRequests,
        observedAverageBatchSize,
        preSnapshot: pre,
        postSnapshot: post,
    };
}

/**
 * Bounded polling for request finalization.
 *
 * @param {string} baseUrl       Dispatch service URL
 * @param {string} requestId     The submitted request ID
 * @param {number} submitTimeMs  Client timestamp when POST /request was sent
 * @param {number} maxAttempts   Maximum polling attempts before timeout
 * @param {number} pollIntervalMs Delay between poll attempts
 * @param {number} initialWaitMs  Initial backoff before first poll
 */
export function pollRequestStatus(
    baseUrl,
    requestId,
    submitTimeMs,
    maxAttempts = 10,
    pollIntervalMs = 25,
    initialWaitMs = 30
) {
    sampledRequests.add(1);

    if (initialWaitMs > 0) {
        sleep(initialWaitMs / 1000);
    }

    const pollUrl = `${baseUrl}/api/requests/${encodeURIComponent(requestId)}`;
    let attempts = 0;

    while (attempts < maxAttempts) {
        attempts++;
        pollRequestCount.add(1);

        const pollRes = http.get(pollUrl, {
            headers: { Accept: 'application/json' },
            tags: { type: 'poll' },
            timeout: '2s',
        });

        if (pollRes.status === 200) {
            try {
                const body = JSON.parse(pollRes.body);
                const status = body.status;

                if (status === 'MATCHED' || status === 'NO_DRIVERS' || status === 'NO_DRIVER') {
                    const stopTimeMs = Date.now();
                    const totalLatency = stopTimeMs - submitTimeMs;
                    // Lower-bound quantization delay estimate (half the poll interval)
                    const obsDelay = Math.max(0, (pollIntervalMs / 2));

                    dispatchLatency.add(totalLatency, { outcome: status });
                    observationDelay.add(obsDelay);
                    pollAttempts.add(attempts);

                    if (status === 'MATCHED') {
                        dispatchSuccessMatched.add(1);
                    } else {
                        dispatchSuccessNoDrivers.add(1);
                    }

                    return {
                        resolved: true,
                        status: status,
                        latency: totalLatency,
                        driverId: body.driverId || null,
                        attempts: attempts,
                    };
                }
                // Status is PENDING; wait for next poll
            } catch (e) {
                // Ignore parse errors on transient payloads; retry next cycle
            }
        }

        if (attempts < maxAttempts) {
            sleep(pollIntervalMs / 1000);
        }
    }

    // Timeout: reached maxAttempts without observing terminal outcome
    const timeoutDuration = Date.now() - submitTimeMs;
    dispatchTimeout.add(1);
    dispatchLatency.add(timeoutDuration, { outcome: 'TIMEOUT' });
    pollAttempts.add(attempts);

    return {
        resolved: false,
        status: 'TIMEOUT',
        latency: timeoutDuration,
        attempts: attempts,
    };
}
