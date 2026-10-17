/**
 * k6/low-load.js
 *
 * Low-Load Benchmark Scenario for Rendezvous Dispatch Platform.
 *
 * <h2>Workload Specification</h2>
 * <ul>
 *   <li><strong>Executor:</strong> Open-loop {@code constant-arrival-rate} (avoids Coordinated Omission).</li>
 *   <li><strong>Target Rate:</strong> 3 requests/second.</li>
 *   <li><strong>Steady-State Duration:</strong> 60 seconds (total expected requests: 180).</li>
 *   <li><strong>VU Allocation:</strong> {@code preAllocatedVUs: 10}, {@code maxVUs: 50}.</li>
 * </ul>
 *
 * <h2>Phase 8 Success Criteria & Hypotheses</h2>
 * <ul>
 *   <li><strong>Low-Load Latency Target:</strong> p95 end-to-end dispatch latency &le; 50 ms.</li>
 *   <li><strong>No Batching Stalls:</strong> Under sparse arrivals (&lambda; &lt; 2.0 req/s), the
 *       {@code AdaptiveWindowController} activates low-traffic prompt processing, ensuring isolated
 *       requests are processed immediately without waiting for the 200 ms batch accumulation window.</li>
 *   <li><strong>Expected Batch Size:</strong> Observed average batch size &asymp; 1.0.</li>
 * </ul>
 *
 * <h2>Execution Instructions</h2>
 * <pre>
 *   # 1. Ensure backend services are running (postgres, redis, kafka, dispatch application)
 *   # 2. Seed the driver pool (idempotent, fresh):
 *   node scripts/seed-drivers.js --count 50
 *   # 3. Run the benchmark:
 *   k6 run k6/low-load.js
 *   # Or with custom base URL:
 *   k6 run -e DISPATCH_BASE_URL=http://localhost:8080 k6/low-load.js
 * </pre>
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import {
    getBaseUrl,
    generateRequestId,
    generateRidePayload,
    fetchBatchTelemetry,
    calculateBatchDelta,
    pollRequestStatus,
    ingestionLatency,
    dispatchFailure,
} from './common.js';

// =============================================================================
// k6 Configuration Options
// =============================================================================

export const options = {
    scenarios: {
        low_load_arrival: {
            executor: 'constant-arrival-rate',
            rate: 3,
            timeUnit: '1s',
            duration: '60s',
            preAllocatedVUs: 10,
            maxVUs: 50,
        },
    },
    thresholds: {
        // HTTP Ingestion SLA: 99.9% success, p95 < 25ms
        'http_req_failed{type:ingestion}': ['rate<0.001'],
        'ingestion_latency': ['p(95)<25', 'p(99)<50'],

        // Phase 8 Target: p95 dispatch latency <= 50ms
        'dispatch_latency': ['p(95)<50', 'p(99)<100'],

        // System Health Safeguards
        'dispatch_timeout': ['count==0'],
        'dispatch_failure': ['count==0'],
        'dropped_iterations': ['count==0'],
    },
};

// =============================================================================
// Lifecycle Setup & Teardown
// =============================================================================

export function setup() {
    const baseUrl = getBaseUrl();
    console.log(`[low-load] Initializing benchmark against ${baseUrl}`);

    const preSnapshot = fetchBatchTelemetry(baseUrl);
    if (preSnapshot) {
        console.log(`[low-load] Pre-run Telemetry: totalBatches=${preSnapshot.totalBatches}, totalRequests=${preSnapshot.totalRequests}, avgBatchSize=${preSnapshot.averageBatchSize}`);
    } else {
        console.warn('[low-load] Warning: Could not fetch pre-run telemetry from /api/telemetry/batches');
    }

    return {
        baseUrl,
        preSnapshot,
        startTimeMs: Date.now(),
    };
}

export function teardown(data) {
    const baseUrl = data.baseUrl;
    console.log('[low-load] Benchmark complete. Waiting 3s for consumer drain...');
    sleep(3);

    const postSnapshot = fetchBatchTelemetry(baseUrl);
    if (postSnapshot && data.preSnapshot) {
        const delta = calculateBatchDelta(data.preSnapshot, postSnapshot);
        console.log('\n======================================================');
        console.log('LOW-LOAD BATCH TELEMETRY AUDIT');
        console.log('======================================================');
        console.log(`Delta Requests Observed:     ${delta.deltaRequests}`);
        console.log(`Delta Batches Observed:      ${delta.deltaBatches}`);
        console.log(`Observed Run Avg Batch Size: ${delta.observedAverageBatchSize}`);
        console.log(`Post-run Peak Batch Size:    ${postSnapshot.maxObservedBatchSize}`);
        console.log(`Post-run Arrival Rate (EMA): ${postSnapshot.estimatedArrivalRate.toFixed(2)} req/s`);
        console.log('======================================================\n');
    } else {
        console.warn('[low-load] Telemetry delta calculation skipped (snapshot unavailable).');
    }
}

// =============================================================================
// Iteration Execution
// =============================================================================

// Sampling: uniform Bernoulli sample of 20% (~0.6 polls/sec during 3 req/s steady-state)
// Replaces per-VU __ITER % SAMPLE_MODULO to eliminate initial-burst sampling bias
const SAMPLE_RATE = 0.20;

export default function (data) {
    const baseUrl = data.baseUrl;
    const requestId = generateRequestId('low');
    const payload = generateRidePayload(requestId);

    const submitTime = Date.now();
    const res = http.post(`${baseUrl}/request`, payload, {
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json',
        },
        tags: { type: 'ingestion' },
        timeout: '5s',
    });

    const ingestionDuration = Date.now() - submitTime;
    ingestionLatency.add(ingestionDuration);

    const accepted = check(res, {
        'status is 202': (r) => r.status === 202,
        'has requestId in body': (r) => {
            try {
                return JSON.parse(r.body).requestId === requestId;
            } catch (e) {
                return false;
            }
        },
    });

    if (!accepted) {
        dispatchFailure.add(1);
        console.error(`[low-load] Request submission failed: requestId=${requestId}, status=${res.status}, body=${res.body}`);
        return;
    }

    // Sampled status observation: independent 20% Bernoulli sample across all test phases
    if (Math.random() < SAMPLE_RATE) {
        // Initial wait 20ms, poll every 15ms up to 20 attempts (320ms max deadline)
        pollRequestStatus(baseUrl, requestId, submitTime, 20, 15, 20);
    }
}
