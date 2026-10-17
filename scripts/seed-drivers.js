#!/usr/bin/env node
/**
 * scripts/seed-drivers.js
 *
 * Pre-test driver seeding and freshness refresh utility for Rendezvous dispatch benchmarks.
 *
 * <h2>Operational Semantics & Verification</h2>
 * <ul>
 *   <li><strong>Endpoint:</strong> Calls {@code POST /ping} with {@code action: "AUTO"}.
 *       Per {@code DriverPingConsumer.java:228-246}, {@code processAuto()} registers the driver
 *       if absent and updates its coordinates and {@code updatedAt} timestamp if already registered.
 *       This is strictly idempotent and safe to repeat across multiple benchmark runs without
 *       deleting or resetting database state.</li>
 *   <li><strong>Driver Freshness:</strong> Per {@code DriverFreshnessConfig.java:32}, drivers
 *       whose last location update is older than 60 seconds ({@code dispatch.matching.driver-staleness-seconds})
 *       are excluded from {@code DriverRegistry.snapshotFresh()}. Providing current epoch timestamps
 *       ({@code capturedAt: Date.now()}) guarantees all seeded drivers are fresh.</li>
 *   <li><strong>Trip Lifecycle & Claim Capacity:</strong> In this platform, each matched ride request
 *       atomically transitions one driver from {@code AVAILABLE} to {@code CLAIMED} via {@code tryClaim()}.
 *       A claimed driver is not available for subsequent matching until the trip completes or cancels.
 *       Therefore, <strong>a fixed driver pool (e.g. 200 drivers) will match at most 200 requests</strong>.
 *       Additional requests will durably finalize as {@code NO_DRIVERS} in PostgreSQL, which is a legitimate,
 *       fully-persisted terminal dispatch outcome.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 *   node scripts/seed-drivers.js [options]
 *
 * Options:
 *   --base-url <url>    Base URL of dispatch service (default: http://localhost:8080)
 *   --count <num>       Number of drivers to seed (default: 200)
 *   --prefix <str>      Driver ID prefix (default: DRIVER-K6-)
 *   --refresh           Run once to update timestamps of existing driver pool
 *   --loop              Run continuously, refreshing timestamps periodically
 *   --interval <sec>    Refresh interval in seconds when using --loop (default: 30)
 * </pre>
 */

const http = require('http');

// Configuration parsing
const args = process.argv.slice(2);
function getArg(flag, defaultValue) {
    const idx = args.indexOf(flag);
    if (idx !== -1 && idx + 1 < args.length) {
        return args[idx + 1];
    }
    return defaultValue;
}
const hasFlag = (flag) => args.includes(flag);

const BASE_URL = getArg('--base-url', process.env.DISPATCH_BASE_URL || 'http://localhost:8080');
const COUNT = parseInt(getArg('--count', '200'), 10);
const PREFIX = getArg('--prefix', 'DRIVER-K6-');
const IS_LOOP = hasFlag('--loop');
const INTERVAL_SEC = parseInt(getArg('--interval', '30'), 10);

// Base spatial reference: Hyderabad center (lat: 17.3850, lon: 78.4867)
const BASE_LAT = 17.3850;
const BASE_LON = 78.4867;

/**
 * Generates deterministic, spatially distributed coordinates across a 5 km grid.
 */
function getDriverCoordinates(index) {
    const cols = 20;
    const row = Math.floor(index / cols);
    const col = index % cols;
    // Spacing of ~0.003 degrees (~330 meters)
    const latOffset = (row - 5) * 0.003;
    const lonOffset = (col - 10) * 0.003;
    return {
        latitude: parseFloat((BASE_LAT + latOffset).toFixed(6)),
        longitude: parseFloat((BASE_LON + lonOffset).toFixed(6)),
    };
}

/**
 * Sends a single driver ping with action: AUTO.
 */
async function pingDriver(driverId, latitude, longitude, capturedAt) {
    const url = new URL('/ping', BASE_URL);
    const payload = JSON.stringify({
        driverId: driverId,
        latitude: latitude,
        longitude: longitude,
        action: 'AUTO',
        capturedAt: capturedAt,
    });

    return new Promise((resolve) => {
        const req = http.request(
            url,
            {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Content-Length': Buffer.byteLength(payload),
                },
                timeout: 5000,
            },
            (res) => {
                let data = '';
                res.on('data', (chunk) => { data += chunk; });
                res.on('end', () => {
                    if (res.statusCode === 202) {
                        resolve({ success: true, driverId, statusCode: res.statusCode });
                    } else {
                        resolve({ success: false, driverId, statusCode: res.statusCode, error: data });
                    }
                });
            }
        );

        req.on('error', (err) => {
            resolve({ success: false, driverId, statusCode: 0, error: err.message });
        });

        req.on('timeout', () => {
            req.destroy();
            resolve({ success: false, driverId, statusCode: 408, error: 'Request timeout (5s)' });
        });

        req.write(payload);
        req.end();
    });
}

/**
 * Seeds or refreshes the entire driver pool.
 */
async function seedPool(iteration = 1) {
    const now = Date.now();
    console.log(`\n[seed-drivers] --- ${IS_LOOP ? `Iteration ${iteration}: ` : ''}Seeding/Refreshing ${COUNT} drivers at ${new Date(now).toISOString()} ---`);
    console.log(`[seed-drivers] Target: ${BASE_URL}/ping (Action: AUTO, Prefix: ${PREFIX})`);

    const startTime = Date.now();
    let successCount = 0;
    let failureCount = 0;
    const failures = [];

    // Run in concurrency-controlled batches (10 concurrent requests) to avoid local socket exhaustion
    const BATCH_SIZE = 10;
    for (let i = 0; i < COUNT; i += BATCH_SIZE) {
        const batchPromises = [];
        const limit = Math.min(i + BATCH_SIZE, COUNT);

        for (let j = i; j < limit; j++) {
            const driverId = `${PREFIX}${String(j + 1).padStart(4, '0')}`;
            const { latitude, longitude } = getDriverCoordinates(j);
            batchPromises.push(pingDriver(driverId, latitude, longitude, now));
        }

        const results = await Promise.all(batchPromises);
        for (const res of results) {
            if (res.success) {
                successCount++;
            } else {
                failureCount++;
                failures.push(res);
            }
        }
    }

    const elapsedMs = Date.now() - startTime;
    console.log(`[seed-drivers] Complete in ${elapsedMs} ms: ${successCount} accepted (202), ${failureCount} failed.`);

    if (failureCount > 0) {
        console.error(`[seed-drivers] Sample failures (first 3):`);
        failures.slice(0, 3).forEach((f) => {
            console.error(`  - ${f.driverId}: HTTP ${f.statusCode} (${f.error})`);
        });
    }

    return { successCount, failureCount, elapsedMs };
}

// Execution entry point
async function main() {
    let iteration = 1;
    await seedPool(iteration);

    if (IS_LOOP) {
        console.log(`[seed-drivers] Entering heartbeat loop: refreshing driver pool every ${INTERVAL_SEC}s... (Press Ctrl+C to stop)`);
        setInterval(async () => {
            iteration++;
            await seedPool(iteration);
        }, INTERVAL_SEC * 1000);
    }
}

main().catch((err) => {
    console.error('[seed-drivers] Unhandled error:', err);
    process.exit(1);
});
