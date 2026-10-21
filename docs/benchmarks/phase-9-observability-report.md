# Phase 9 Benchmark Evidence Report: Observability Overhead & Load Evaluation

> **Document Status:** Official Benchmark Evidence Record  
> **Target Branch / Architecture:** Phase 9 Observability (Micrometer, Prometheus, Grafana Provisioning)  
> **Date:** October 17, 2026  
> **JVM Compiler Tier:** Full Tiered Compilation (Tier 1 C1 + Tier 2 C2 optimizing JIT enabled; `-XX:TieredStopAtLevel=1` absent)  
> **Baseline Reference:** [Phase 8 Benchmark Evidence Report](phase-8-alternative-a-report.md)

---

## 1. Executive Summary

This report documents the empirical evaluation of the Rendezvous dispatch matching platform under the **Phase 9 Observability Infrastructure** (centralized `DispatchMetrics` facade, Micrometer instrumentation, Actuator Prometheus scrape endpoint, and automated Grafana 21-panel dashboard provisioning).

The primary objective was to measure the runtime and latency overhead introduced by metric collection and continuous Prometheus scraping (every 5 seconds) during peak load (up to 60 requests/second sustained arrival rate).

### Key Headline Results

| Metric Category | Phase 8 Baseline | Phase 9.5 Observed | Target / SLA | Evaluation |
| :--- | :---: | :---: | :---: | :---: |
| **Ingestion Latency (p99)** | $28\text{ ms}$ | **$26\text{ ms}$** | $< 80\text{ ms}$ | **PASS** |
| **Ingestion Latency (p95)** | $24\text{ ms}$ | **$24\text{ ms}$** | $< 40\text{ ms}$ | **PASS** |
| **Ingestion Latency (Mean)** | $14.94\text{ ms}$ | **$15.15\text{ ms}$** | - | **$+0.21\text{ ms}$ overhead** |
| **Dispatch Latency (p99)** | $70\text{ ms}$ | **$66\text{ ms}$** | $< 250\text{ ms}$ | **PASS** |
| **Dispatch Latency (p95)** | $64\text{ ms}$ | **$63\text{ ms}$** | $< 150\text{ ms}$ | **PASS** |
| **Dispatch Latency (Mean)** | $54.55\text{ ms}$ | **$54.61\text{ ms}$** | - | **$+0.06\text{ ms}$ overhead** |
| **Ingestion Availability** | $100.00\%$ | **$100.00\%$** (4,424 / 4,424) | $\ge 99.9\%$ | **PASS** |
| **Dispatch Failures / Timeouts** | $0$ | **$0$** | $0$ | **PASS** |
| **Durable Ingestion Persistence** | $100.00\%$ | **$100.00\%$** (+4,424 rows) | $100\%$ | **PASS** |
| **Observed Mean Batch Size** | $1.12$ | **$1.13$** (Peak: 3) | $\ge 10.0$ | **DEFERRED (Unchanged)** |
| **Automated Test Suite** | 485 passing | **518 / 518 passing (0 skipped)** | 0 failures | **PASS** |

> [!NOTE]
> **Batch Size Status:**  
> The average Kafka poll batch size under load ($1.13$) remains below the architectural design target of $\ge 10.0$. As agreed in Phase 8 and Phase 9, this target remains **explicitly deferred** to a future consumer-tuning milestone.

---

## 2. Environment & Runtime Topology

The benchmark was conducted on the Windows development host with identical JVM compilation settings to Phase 8:

### 2.1 JVM Configuration
- **JDK:** Eclipse Adoptium OpenJDK 17.0.20 (HotSpot)
- **Timezone:** UTC (`-Duser.timezone=UTC`)
- **JIT Compilation:** Full Tiered Compilation active (Tier 1 C1 client compiler + Tier 2 C2 optimizing server compiler; `-XX:TieredStopAtLevel=1` was confirmed absent).

### 2.2 Infrastructure Services Topology
- **Backend Application:** Spring Boot (`dispatch`) on host port `8080`.
- **Database:** PostgreSQL 16 on host port `5434` (container `dispatch-postgres-1`).
- **Cache / Location Store:** Redis 7 on host port `6380` (container `dispatch-redis-1`).
- **Message Broker:** Apache Kafka 3.7.0 on host port `9092` (container `dispatch-kafka-1`).
- **Metrics Collector:** Prometheus v2.54.1 on host port `9090` (container `dispatch-prometheus`), scraping `http://host.docker.internal:8080/actuator/prometheus` at 5-second intervals.
- **Observability UI:** Grafana 11.2.0 on host port `3000` (container `dispatch-grafana`), serving the provisioned 21-panel dashboard.
- **Load Generator:** Containerized `grafana/k6` communicating with the backend via `http://host.docker.internal:8080`.

---

## 3. Workload Specification & Reproducibility

The benchmark executed the identical high-load workload scenario defined in `k6/high-load.js`:

### 3.1 Workload Profile
- **Executor:** Open-loop `ramping-arrival-rate` (eliminates Coordinated Omission).
- **Workload Stages:**
  1. *Ramp-up:* 10 $\rightarrow$ 60 req/s over 15 seconds (warms up JIT compilation, thread pools, and database connection pools).
  2. *Steady-State:* 60 req/s sustained for 60 seconds (~3,600 requests).
  3. *Drain Window:* 60 $\rightarrow$ 0 req/s ramp-down over 10 seconds.
- **Workload Duration:** 85 seconds active load + 5 seconds drain sleep.
- **Virtual Users (VUs):** `preAllocatedVUs: 60`, `maxVUs: 250` (zero dropped iterations).
- **Total Requests Submitted:** 4,424 requests.

### 3.2 Exact Reproducibility Instructions

To reproduce the benchmark without altering existing historical database records:

#### Step 1: Preflight Health & Telemetry Verification
```bash
# Verify backend liveness and readiness
curl -s http://localhost:8080/actuator/health

# Verify Prometheus metrics endpoint
curl -s http://localhost:8080/actuator/prometheus | head -n 20

# Capture initial batch telemetry snapshot
curl -s http://localhost:8080/api/telemetry/batches
```

#### Step 2: Seed Fresh Driver Fleet
Seed 250 fresh drivers using a distinct run prefix to avoid conflicting with prior runs:
```bash
node scripts/seed-drivers.js --prefix DRIVER-HLOAD-P9- --count 250
```

#### Step 3: Verify Driver Freshness
Verify that seeded drivers were registered in the in-memory registry and Redis cache:
```bash
curl -s http://localhost:8080/drivers/DRIVER-HLOAD-P9-0001/location
# Or via the proxy alias:
curl -s http://localhost:8080/api/drivers/DRIVER-HLOAD-P9-0001/location
```
*(Confirms HTTP 200 with `"fresh": true`)*.

#### Step 4: Run the High-Load Benchmark
Execute the containerized k6 load runner:
```bash
docker run --rm -v "${PWD}/k6:/k6" -e DISPATCH_BASE_URL=http://host.docker.internal:8080 grafana/k6 run /k6/high-load.js
```

#### Step 5: Post-Run Persistence & Fleet Reconciliation
```bash
docker exec dispatch-postgres-1 psql -U dispatch_user -d dispatch_db -c \
  "SELECT status, count(*) FROM ride_requests WHERE request_id LIKE 'REQ-%' GROUP BY status;" \
  -c "SELECT count(*) as total_assignments FROM assignments;" \
  -c "SELECT count(*) as total_trips FROM trips;"
```

---

## 4. Side-by-Side Performance Comparison

The table below contrasts the Phase 8 baseline (un-instrumented baseline) with Phase 9.5 (active Micrometer instrumentation, Actuator scrape exposure, and 5-second Prometheus polling):

| Metric | Phase 8 Baseline | Phase 9.5 Observed | Delta | Operational SLA |
| :--- | :---: | :---: | :---: | :---: |
| **Ingestion Availability (202)** | 100.00% (4,424/4,424) | 100.00% (4,424/4,424) | $0.00\%$ | $\ge 99.9\%$ |
| **Ingestion Latency (Mean)** | $14.94\text{ ms}$ | $15.15\text{ ms}$ | $+0.21\text{ ms}$ | - |
| **Ingestion Latency (p95)** | $24\text{ ms}$ | $24\text{ ms}$ | $0\text{ ms}$ | $< 40\text{ ms}$ |
| **Ingestion Latency (p99)** | $28\text{ ms}$ | $26\text{ ms}$ | $-2\text{ ms}$ | $< 80\text{ ms}$ |
| **Dispatch Latency (Mean)** | $54.55\text{ ms}$ | $54.61\text{ ms}$ | $+0.06\text{ ms}$ | - |
| **Dispatch Latency (p95)** | $64\text{ ms}$ | $63\text{ ms}$ | $-1\text{ ms}$ | $< 150\text{ ms}$ |
| **Dispatch Latency (p99)** | $70\text{ ms}$ | $66\text{ ms}$ | $-4\text{ ms}$ | $< 250\text{ ms}$ |
| **Dispatch Failures** | 0 | 0 | 0 | 0 |
| **Dispatch Timeouts** | 0 | 0 | 0 | 0 |
| **Dropped Iterations** | 0 | 0 | 0 | 0 |
| **Requests Persisted in PostgreSQL**| +4,424 rows | +4,424 rows | 0 | 100% |
| **Mean Batch Size** | 1.12 (Peak: 3) | 1.13 (Peak: 3) | $+0.01$ | $\ge 10.0$ (deferred) |

---

## 5. Observability Overhead Assessment

### 5.1 Direct Latency Overhead
- **Ingestion Path:** Mean ingestion duration increased by **$+0.21\text{ ms}$** (from $14.94\text{ ms}$ to $15.15\text{ ms}$). Percentile figures remained virtually identical (p95 at $24\text{ ms}$, p99 at $26\text{ ms}$). This delta is within normal run-to-run variance.
- **Dispatch Processing Path:** Mean dispatch latency shifted by **$+0.06\text{ ms}$** (from $54.55\text{ ms}$ to $54.61\text{ ms}$). Tail latency remained well within SLA limits (p95 at $63\text{ ms}$, p99 at $66\text{ ms}$ vs. the $250\text{ ms}$ SLA).
- **Design Rationale:** In `DispatchMetrics`, meters, counters, distribution summaries, and timers are pre-registered once during Spring application startup. The hot processing path invokes lightweight atomic counters without dynamic string construction or hash-map lookups, preventing CPU and memory allocation overhead.

### 5.2 System Resource & Garbage Collection Impact
- **Prometheus Scrape Execution:** Each 5-second scrape against `/actuator/prometheus` completed in **$37\text{ ms} - 56\text{ ms}$**, having negligible impact on request processing threads.
- **Heap Footprint:** Total JVM heap memory remained stable at **$\sim 52\text{ MB}$** (Eden: $2\text{ MB}$, Old Generation: $49.5\text{ MB}$, Survivor: $0.66\text{ MB}$). No memory leaks or unbounded tag cardinality were observed.
- **Process CPU:** Idle CPU usage was **$\sim 0.18\%$**. Under peak 60 req/s load, CPU utilization showed brief operational spikes, recovering immediately to baseline post-run.

---

## 6. Live Dashboard & Metrics Validation

During the active high-load run, the Grafana dashboard (**"Dispatch Matching Engine Overview"**) was observed live:
1. **Inbound Rate:** Scraped counters registered exactly **4,424** ride requests (`action="RIDE_REQUEST"`).
2. **Durable Persistence Counter:** Recorded exactly **4,424** successful persistence writes with zero write failures.
3. **Matching Evaluation Count:** Recorded **3,926** batch matching evaluations, matching 1:1 with consumer poll batches.
4. **Fleet fresh gauge:** Correctly reflected 250 fresh drivers at start, transitioning to claimed as matches occurred.
5. **Panel Health:** All 21 Grafana panels rendered without query syntax errors, missing series, or colliding legend entries.

---

## 7. Batch Telemetry Audit & Deferred Batch Target

From the consumer-side telemetry endpoint (`GET /api/telemetry/batches`):

```
======================================================
PHASE 9.5 HIGH-LOAD BATCH TELEMETRY AUDIT
======================================================
Delta Requests Observed:     4,424
Delta Batches Observed:      3,926
Observed Run Avg Batch Size: 1.13
Phase 8 Target Met (>= 10):  FAIL [BELOW TARGET]
Post-run Peak Batch Size:    3
Post-run Arrival Rate (EMA): 0.01 req/s
======================================================
```

### Technical Root Cause
- The Spring Kafka `ConcurrentKafkaListenerContainerFactory` consumes records as soon as they become available. At 60 req/s, one request arrives approximately every $16.7\text{ ms}$.
- Because the matching and database write pipeline completes each batch in $2\text{--}4\text{ ms}$, the consumer loop immediately repolls the broker rather than accumulating records up to the batch limit.
- **Resolution Status:** Tuning Kafka consumer fetch parameters (`fetch.min.bytes` and `fetch.max.wait.ms`) to achieve higher average batch sizes remains **explicitly deferred** to subsequent platform milestones.

---

## 8. Database Reconciliation & Fleet Integrity

```sql
-- 1. Total Ingestion Reconciliation
Delta Requests Persisted: +4,424 (100.0% of all submitted requests durably committed)

-- 2. Fleet Utilization (DRIVER-HLOAD-P9-*)
Total drivers seeded:        250
Assignments created:         250
Distinct drivers assigned:   250 (100.0% utilization, 0 duplicate assignments)
Trips created:               250

-- 3. Invariant Integrity Checks
Orphan assignments:            0 (assignments with missing trip or request)
Orphan trips:                  0 (trips with missing assignment or request)
Duplicate assignment requests: 0 (requests with > 1 assignment record)
```

---

## 9. Full Automated Test Suite Results

Following benchmark execution, the entire automated test suite was executed against active Docker dependencies:

- **Command:** `./mvnw test`
- **Result:** `BUILD SUCCESS`
- **Backend Test Count:** **518 Tests across 33 Test Classes** (465 unit/slice + 53 integration tests)
- **Failures:** **0**
- **Errors:** **0**
- **Skipped:** **0**
- **Frontend Test Suite:** **122 Tests across 11 Test Files** (`npm test` in `frontend/`), 100% passing.

---

## 10. Conclusion & Acceptance Recommendation

1. **Overhead Conclusion:** Micrometer metrics instrumentation and 5-second Prometheus scraping introduce negligible overhead ($+0.21\text{ ms}$ on ingestion, $+0.06\text{ ms}$ on dispatch), while maintaining 100% ingestion availability, 100% durable persistence, and zero errors.
2. **Acceptance Recommendation:** Phase 9 observability is verified, validated under full C2 load, and recommended for production operations.
