/**
 * tests/k6-dispatch-stress.js
 *
 * Performance CI Gate — Rendezvous Dispatch Engine
 *
 * This script serves as a strict latency contract enforced in CI/CD via
 * .github/workflows/perf-gate.yml. The gate fails the PR if p99 dispatch
 * assignment latency exceeds 100ms.
 *
 * Workload specification:
 *   - 200 drivers registered at geographic center (Hyderabad) before ramp-up.
 *   - 4,500+ ride requests at 60 req/s sustained (matches the original Phase 8 benchmark).
 *   - 100% HTTP 202 ingestion acceptance required.
 *   - p99 end-to-end dispatch latency <= 100ms (CI gate threshold).
 *   - p95 dispatch latency <= 75ms (stricter inner target).
 *
 * Usage:
 *   k6 run tests/k6-dispatch-stress.js
 *   k6 run -e DISPATCH_BASE_URL=http://localhost:8080 tests/k6-dispatch-stress.js
 *   k6 run --out json=k6-results.json tests/k6-dispatch-stress.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';

// =============================================================================
// Custom Metrics
// =============================================================================

const ingestionLatency  = new Trend('ingestion_latency', true);
const dispatchLatency   = new Trend('dispatch_latency', true);
const dlqPollLatency    = new Trend('dlq_poll_latency', true);

const dispatchMatched   = new Counter('dispatch_matched');
const dispatchNoDrivers = new Counter('dispatch_no_drivers');
const dispatchTimeout   = new Counter('dispatch_timeout');
const dispatchFailure   = new Counter('dispatch_failure');

const ingestionSuccessRate = new Rate('ingestion_success_rate');

// =============================================================================
// Scenario Configuration
// =============================================================================

export const options = {
    scenarios: {
        dispatch_stress: {
            executor: 'ramping-arrival-rate',
            startRate: 5,
            timeUnit: '1s',
            stages: [
                // Ramp-up: allow JIT warmup, Kafka consumer rebalance, DB pool warm.
                { target: 60, duration: '15s' },
                // Steady-state: sustained 60 req/s for 75 seconds ≈ 4,500 requests.
                { target: 60, duration: '75s' },
                // Drain: give consumers time to finalize in-flight batches.
                { target: 0,  duration: '10s' },
            ],
            preAllocatedVUs: 80,
            maxVUs: 300,
        },
    },

    // =========================================================================
    // Performance Gate Thresholds
    // =========================================================================
    // Any threshold failure causes k6 to exit with code 99,
    // which the CI workflow translates to a non-zero pipeline exit code.
    thresholds: {
        // --- GATE 1: HTTP Ingestion Acceptance (hard gate) ---
        // 100% of POST /request calls must return HTTP 202.
        // A failure here means the API layer is dropping requests, which is unacceptable.
        'http_req_failed{type:ingestion}': [
            { threshold: 'rate<0.001', abortOnFail: false },
        ],
        'ingestion_success_rate': [
            { threshold: 'rate>0.999', abortOnFail: false },
        ],
        // Ingestion p99 must be < 80ms (broker roundtrip).
        'ingestion_latency': [
            { threshold: 'p(95)<40', abortOnFail: false },
            { threshold: 'p(99)<80', abortOnFail: false },
        ],

        // --- GATE 2: End-to-End Dispatch Latency (primary CI gate) ---
        // p99 must be <= 100ms. Exceeding this fails the PR.
        // p95 must be <= 75ms (inner target for system health).
        'dispatch_latency': [
            { threshold: 'p(95)<75',  abortOnFail: false },
            { threshold: 'p(99)<100', abortOnFail: true },  // PRIMARY GATE: aborts test on breach
        ],

        // --- GATE 3: Zero Timeouts ---
        // Any timeout means the consumer is lagging dangerously behind ingest rate.
        'dispatch_timeout': [
            { threshold: 'count==0', abortOnFail: false },
        ],

        // --- GATE 4: Zero Ingestion Failures ---
        'dispatch_failure': [
            { threshold: 'count==0', abortOnFail: false },
        ],
    },
};

// =============================================================================
// Utility functions
// =============================================================================

function getBaseUrl() {
    return __ENV.DISPATCH_BASE_URL || 'http://localhost:8080';
}

function generateRequestId() {
    const vu   = typeof __VU   !== 'undefined' ? __VU   : 0;
    const iter = typeof __ITER !== 'undefined' ? __ITER : 0;
    return `ci-gate-v${vu}-i${iter}-${Date.now()}-${Math.floor(Math.random() * 99999)}`;
}

function generateRidePayload(requestId) {
    // Cluster requests around Hyderabad center where drivers are seeded.
    const lat = 17.3850 + (Math.random() - 0.5) * 0.04;
    const lon = 78.4867 + (Math.random() - 0.5) * 0.04;
    return JSON.stringify({
        requestId: requestId,
        latitude:  parseFloat(lat.toFixed(6)),
        longitude: parseFloat(lon.toFixed(6)),
    });
}

function registerDriver(baseUrl, driverId, lat, lon) {
    const payload = JSON.stringify({
        driverId:  driverId,
        latitude:  lat,
        longitude: lon,
        timestamp: Date.now(),
        action:    'REGISTER',
    });
    const res = http.post(`${baseUrl}/api/register`, payload, {
        headers: { 'Content-Type': 'application/json' },
        timeout: '5s',
        tags:    { type: 'setup' },
    });
    return res.status === 202;
}

/**
 * Bounded polling for dispatch terminal state.
 * Returns the observed latency in ms, or null on timeout.
 */
function pollForTermination(baseUrl, requestId, submitTimeMs) {
    const maxAttempts    = 12;
    const initialWaitMs  = 30;
    const pollIntervalMs = 25;

    sleep(initialWaitMs / 1000);

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        const pollRes = http.get(
            `${baseUrl}/api/requests/${encodeURIComponent(requestId)}`,
            { headers: { Accept: 'application/json' }, timeout: '2s', tags: { type: 'poll' } }
        );

        if (pollRes.status === 200) {
            try {
                const body   = JSON.parse(pollRes.body);
                const status = body.status;
                if (status === 'MATCHED' || status === 'NO_DRIVERS' || status === 'NO_DRIVER') {
                    const latency = Date.now() - submitTimeMs;
                    dispatchLatency.add(latency, { outcome: status });
                    if (status === 'MATCHED') {
                        dispatchMatched.add(1);
                    } else {
                        dispatchNoDrivers.add(1);
                    }
                    return latency;
                }
            } catch (_) { /* transient parse error — retry */ }
        }

        if (attempt < maxAttempts) sleep(pollIntervalMs / 1000);
    }

    // Timeout: p99 gate will catch this if it occurs in any significant volume.
    const elapsed = Date.now() - submitTimeMs;
    dispatchTimeout.add(1);
    dispatchLatency.add(elapsed, { outcome: 'TIMEOUT' });
    return null;
}

// =============================================================================
// Setup: Seed 200 drivers at Hyderabad center (runs once, single VU)
// =============================================================================

export function setup() {
    const baseUrl     = getBaseUrl();
    const driverCount = 200;

    console.log(`[CI-Gate] Seeding ${driverCount} drivers around Hyderabad center (17.385, 78.4867)...`);

    let registered = 0;
    for (let i = 0; i < driverCount; i++) {
        const driverId = `ci-driver-${i}-${Date.now()}`;
        const lat = 17.3850 + (Math.random() - 0.5) * 0.04;
        const lon = 78.4867 + (Math.random() - 0.5) * 0.04;

        if (registerDriver(baseUrl, driverId, lat, lon)) {
            registered++;
        }
    }

    // Give Kafka consumer time to process all ping registrations.
    sleep(3);
    console.log(`[CI-Gate] Driver seeding complete: ${registered}/${driverCount} registered.`);

    return { baseUrl, startTimeMs: Date.now() };
}

// =============================================================================
// Default iteration: Submit → (sampled) Poll
// =============================================================================

// Sample 10% of requests for end-to-end latency measurement.
// At 60 req/s this gives ~6 dispatch observations/second — enough for percentile accuracy.
const SAMPLE_RATE = 0.10;

export default function (data) {
    const baseUrl   = data.baseUrl;
    const requestId = generateRequestId();
    const payload   = generateRidePayload(requestId);

    const submitTime = Date.now();
    const res = http.post(`${baseUrl}/request`, payload, {
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        tags:    { type: 'ingestion' },
        timeout: '5s',
    });

    const ingestionDurationMs = Date.now() - submitTime;
    ingestionLatency.add(ingestionDurationMs);

    const accepted = res.status === 202;
    ingestionSuccessRate.add(accepted ? 1 : 0);

    const bodyOk = check(res, {
        'POST /request → HTTP 202': (r) => r.status === 202,
        'response body has requestId': (r) => {
            try { return JSON.parse(r.body).requestId === requestId; } catch (_) { return false; }
        },
    });

    if (!accepted || !bodyOk) {
        dispatchFailure.add(1);
        console.error(`[CI-Gate] Ingestion failure: requestId=${requestId} status=${res.status}`);
        return;
    }

    // Sampled end-to-end latency measurement.
    if (Math.random() < SAMPLE_RATE) {
        pollForTermination(baseUrl, requestId, submitTime);
    }
}

// =============================================================================
// Teardown: drain & report
// =============================================================================

export function teardown(data) {
    console.log('[CI-Gate] Steady-state complete. Waiting 5s for consumer drain...');
    sleep(5);

    const baseUrl = data.baseUrl;
    const telRes  = http.get(`${baseUrl}/api/telemetry/batches`, {
        headers: { Accept: 'application/json' }, timeout: '5s',
    });

    if (telRes.status === 200) {
        try {
            const tel = JSON.parse(telRes.body);
            console.log('\n================================================================');
            console.log('CI PERFORMANCE GATE — BATCH TELEMETRY SUMMARY');
            console.log('================================================================');
            console.log(`Total Batches Processed : ${tel.totalBatches}`);
            console.log(`Total Requests Processed: ${tel.totalRequests}`);
            console.log(`Average Batch Size      : ${tel.averageBatchSize}`);
            console.log(`Peak Batch Size         : ${tel.maxObservedBatchSize}`);
            console.log(`EMA Arrival Rate (req/s): ${(tel.estimatedArrivalRate || 0).toFixed(2)}`);
            console.log('================================================================\n');
        } catch (_) {
            console.warn('[CI-Gate] Could not parse telemetry response.');
        }
    } else {
        console.warn(`[CI-Gate] Telemetry endpoint returned HTTP ${telRes.status}`);
    }
}
