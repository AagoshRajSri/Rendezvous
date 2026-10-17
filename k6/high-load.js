/**
 * k6/high-load.js
 *
 * High-Load Benchmark Scenario for Rendezvous Dispatch Platform.
 *
 * <h2>Workload Specification</h2>
 * <ul>
 *   <li><strong>Executor:</strong> Open-loop {@code ramping-arrival-rate} (avoids Coordinated Omission).</li>
 *   <li><strong>Ramp-Up:</strong> 10 &rarr; 60 req/s over 15 seconds (warm up JIT, thread pools, and connections).</li>
 *   <li><strong>Steady-State:</strong> 60 req/s sustained for 60 seconds (~3,600 requests).</li>
 *   <li><strong>Drain:</strong> Ramp down to 0 req/s over 10 seconds.</li>
 *   <li><strong>VU Allocation:</strong> {@code preAllocatedVUs: 60}, {@code maxVUs: 250} (prevents dropped iterations).</li>
 * </ul>
 *
 * <h2>Phase 8 Success Criteria & Hypotheses</h2>
 * <ul>
 *   <li><strong>High-Load Latency Target:</strong> p99 end-to-end dispatch latency &le; 250 ms.</li>
 *   <li><strong>Batch Efficiency Target:</strong> Observed run average batch size &ge; 10.</li>
 *   <li><strong>Durable Ingestion:</strong> 100% of submitted requests acknowledged by broker (HTTP 202)
 *       and durably committed in PostgreSQL with zero dropped records or uncommitted offsets.</li>
 * </ul>
 *
 * <h2>Execution Instructions</h2>
 * <pre>
 *   # 1. Ensure backend services are running (postgres, redis, kafka, dispatch application)
 *   # 2. Seed the driver pool (recommend 200+ drivers):
 *   node scripts/seed-drivers.js --count 200
 *   # 3. Optional: Run periodic driver heartbeat in a background terminal to maintain freshness:
 *   node scripts/seed-drivers.js --count 200 --loop --interval 30
 *   # 4. Run the benchmark:
 *   k6 run k6/high-load.js
 *   # Or with custom base URL:
 *   k6 run -e DISPATCH_BASE_URL=http://localhost:8080 k6/high-load.js
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
        high_load_ramping: {
            executor: 'ramping-arrival-rate',
            startRate: 10,
            timeUnit: '1s',
            stages: [
                { target: 60, duration: '15s' }, // Ramp-up & connection warmup
                { target: 60, duration: '60s' }, // Steady-state measurement window
                { target: 0,  duration: '10s' }, // Drain window
            ],
            preAllocatedVUs: 60,
            maxVUs: 250,
        },
    },
    thresholds: {
        // HTTP Ingestion SLA: 99.9% success, p95 < 40ms, p99 < 80ms
        'http_req_failed{type:ingestion}': ['rate<0.001'],
        'ingestion_latency': ['p(95)<40', 'p(99)<80'],

        // Phase 8 Target: p99 dispatch latency <= 250ms
        'dispatch_latency': ['p(95)<150', 'p(99)<250'],

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
    console.log(`[high-load] Initializing benchmark against ${baseUrl}`);

    const preSnapshot = fetchBatchTelemetry(baseUrl);
    if (preSnapshot) {
        console.log(`[high-load] Pre-run Telemetry: totalBatches=${preSnapshot.totalBatches}, totalRequests=${preSnapshot.totalRequests}, avgBatchSize=${preSnapshot.averageBatchSize}`);
    } else {
        console.warn('[high-load] Warning: Could not fetch pre-run telemetry from /api/telemetry/batches');
    }

    return {
        baseUrl,
        preSnapshot,
        startTimeMs: Date.now(),
    };
}

export function teardown(data) {
    const baseUrl = data.baseUrl;
    console.log('[high-load] Benchmark complete. Waiting 5s for consumer drain and DB persistence finalization...');
    sleep(5);

    const postSnapshot = fetchBatchTelemetry(baseUrl);
    if (postSnapshot && data.preSnapshot) {
        const delta = calculateBatchDelta(data.preSnapshot, postSnapshot);
        const targetMet = delta.observedAverageBatchSize >= 10.0;

        console.log('\n======================================================');
        console.log('HIGH-LOAD BATCH TELEMETRY AUDIT');
        console.log('======================================================');
        console.log(`Delta Requests Observed:     ${delta.deltaRequests}`);
        console.log(`Delta Batches Observed:      ${delta.deltaBatches}`);
        console.log(`Observed Run Avg Batch Size: ${delta.observedAverageBatchSize}`);
        console.log(`Phase 8 Target Met (>= 10):  ${targetMet ? 'PASS [OK]' : 'FAIL [BELOW TARGET]'}`);
        console.log(`Post-run Peak Batch Size:    ${postSnapshot.maxObservedBatchSize}`);
        console.log(`Post-run Arrival Rate (EMA): ${postSnapshot.estimatedArrivalRate.toFixed(2)} req/s`);
        console.log('======================================================\n');
    } else {
        console.warn('[high-load] Telemetry delta calculation skipped (snapshot unavailable).');
    }
}

// =============================================================================
// Iteration Execution
// =============================================================================

// Sampling: uniform Bernoulli sample of 5% (~3 polls/sec during 60 req/s steady-state)
// Replaces per-VU __ITER % SAMPLE_MODULO to eliminate initial-burst sampling bias
const SAMPLE_RATE = 0.05;

export default function (data) {
    const baseUrl = data.baseUrl;
    const requestId = generateRequestId('hi');
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
        console.error(`[high-load] Request submission failed: requestId=${requestId}, status=${res.status}, body=${res.body}`);
        return;
    }

    // Sampled status observation: independent 5% Bernoulli sample across all test phases
    if (Math.random() < SAMPLE_RATE) {
        // Initial wait 35ms, poll every 25ms up to 10 attempts (285ms max deadline)
        pollRequestStatus(baseUrl, requestId, submitTime, 10, 25, 35);
    }
}
