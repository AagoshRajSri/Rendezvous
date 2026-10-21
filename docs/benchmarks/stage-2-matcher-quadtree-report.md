# Phase 11 Stage 2 & Phase D Benchmark Evidence Report: Matcher Latency & Controlled QuadTree Performance

> **Document Status:** Official Benchmark Evidence Record (Revised Post-Audit)  
> **Revisions Evaluated:** Baseline [`374db9e2510d023be208ef314a482d99c928f7ce`](file:///c:/Aagosh/CODE/JAVA/dispatch) vs. Phase C Optimized [`de17a2ba54536cc92776ffe104e0e7e1e353ab2b`](file:///c:/Aagosh/CODE/JAVA/dispatch)  
> **Date:** October 19, 2026  
> **JVM Compiler Tier:** Full Tiered Compilation (Tier 1 C1 + Tier 4 C2 optimizing JIT enabled; Java HotSpot 26.0.1+8-34 64-Bit Server VM)  
> **Hardware:** 13th Gen Intel(R) Core(TM) i7-1360P (12 Cores: 4 P-cores / 8 threads + 8 E-cores / 8 threads, 16 Logical Processors)  
> **Raw JMH Evidence Logs:** Baseline: [`task-15033.log`](file:///C:/Users/aagos/.gemini/antigravity-ide/brain/724806d5-32a9-4d07-a64c-72e6fccf901c/.system_generated/tasks/task-15033.log) | Optimized: [`task-15041.log`](file:///C:/Users/aagos/.gemini/antigravity-ide/brain/724806d5-32a9-4d07-a64c-72e6fccf901c/.system_generated/tasks/task-15041.log)

---

## 1. Executive Summary

This report delivers the empirical, reproducible evidence base for **Claim 2 (Matching Algorithm Execution Latency)** and **Claim 4 (QuadTree Spatial Indexing Performance)** for the Rendezvous dispatch system.

All measurements were collected in memory, isolated from network hops, Kafka messaging, Redis caches, and database transactions, to evaluate pure algorithmic and data-structure execution latency.

### 1.1 Summary Statement of QuadTree Findings

> The optimized QuadTree showed substantially lower observed mean latency in the tested top-10 nearest-neighbor microbenchmarks, particularly for datasets containing 10,000-100,000 points. Several scenarios exhibited iteration-level or fork-level variability, so the reported speedups are descriptive measurements rather than formally calibrated ratio confidence intervals. These results do not establish equivalent gains in end-to-end dispatch performance.

### 1.2 Headline Comparison Table (Controlled Phase D Benchmark, $K=10$, Hyderabad Query)

All spatial query latencies below reflect the controlled comparison protocol: 3 independent JVM forks, 5 warmup iterations, 8 measurement iterations per fork ($N = 24$ measurement iterations per scenario) executed under identical JVM and hardware conditions.

| Benchmark Scenario | Metric | Baseline QuadTree (`374db9e`) | Optimized QuadTree (`de17a2b`) | Point Speedup ($\mu_B / \mu_O$) | Descriptive Ratio Bounds [Formula 1] |
|:---|:---|:---:|:---:|:---:|:---:|
| **Uniform $N=100$** | Query Latency | $18.69\text{ µs} \pm 1.23\text{ µs}$ | **$14.33\text{ µs} \pm 1.44\text{ µs}$** | **$1.30\times$** | $[1.11\times,\; 1.55\times]$ |
| **Uniform $N=1\,000$** | Query Latency | $55.62\text{ µs} \pm 17.66\text{ µs}$ | **$20.70\text{ µs} \pm 1.40\text{ µs}$** | **$2.69\times$** | $[1.72\times,\; 3.80\times]$ |
| **Uniform $N=10\,000$** | Query Latency | $301.99\text{ µs} \pm 12.08\text{ µs}$ | **$18.07\text{ µs} \pm 1.03\text{ µs}$** | **$16.71\times$** | $[15.18\times,\; 18.43\times]$ |
| **Uniform $N=100\,000$** | Query Latency | $1\,034.41\text{ µs} \pm 111.57\text{ µs}$ | **$31.74\text{ µs} \pm 1.84\text{ µs}$** | **$32.60\times$** | $[27.49\times,\; 38.33\times]$ |
| **Clustered $N=100$** | Query Latency | $28.22\text{ µs} \pm 1.47\text{ µs}$ | **$16.66\text{ µs} \pm 0.93\text{ µs}$** | **$1.69\times$** | $[1.52\times,\; 1.89\times]$ |
| **Clustered $N=1\,000$** | Query Latency | $113.63\text{ µs} \pm 5.34\text{ µs}$ | **$45.91\text{ µs} \pm 2.92\text{ µs}$** | **$2.48\times$** | $[2.22\times,\; 2.77\times]$ |
| **Clustered $N=10\,000$** | Query Latency | $270.61\text{ µs} \pm 40.06\text{ µs}$ | **$20.41\text{ µs} \pm 4.40\text{ µs}$** | **$13.26\times$** | $[9.29\times,\; 19.41\times]$ |
| **Clustered $N=100\,000$** | Query Latency | $908.45\text{ µs} \pm 37.54\text{ µs}$ | **$22.15\text{ µs} \pm 3.85\text{ µs}$** | **$41.01\times$** | $[33.49\times,\; 51.69\times]$ |

*(Matching algorithm benchmarks: GreedyMatcher streaming median p50: $37.4\text{ µs}$; AuctionMatcher median p50: $72.0\text{ µs}$ at $R=1, D=50$; details in Section 5).*

---

## 2. Environment & Test Methodology

### 2.1 Host & Runtime Environment
- **Repository:** `C:\Aagosh\CODE\JAVA\dispatch`
- **Revisions Evaluated:**
  - Baseline: Commit `374db9e2510d023be208ef314a482d99c928f7ce`
  - Optimized: Commit `de17a2ba54536cc92776ffe104e0e7e1e353ab2b`
- **Host CPU:** 13th Gen Intel(R) Core(TM) i7-1360P, 12 physical cores (4 Performance cores with 8 threads + 8 Efficient cores with 8 threads, 16 logical processors)
- **RAM / Operating System:** 64-bit Windows 11 (Build 10.0.26100)
- **JVM Runtime:** OpenJDK 26.0.1+8-34, Java HotSpot(TM) 64-Bit Server VM
- **Execution Flags:** Default JVM ergonomics (`VM options: <none>`); tiered compilation active; G1GC default collector; Compiler Blackholes auto-detected by JMH 1.37.

### 2.2 Benchmarking Harnesses & Execution Protocol
1. **QuadTree Controlled Benchmark (`QuadTreeBenchmark.java`):**
   - **Framework:** OpenJDK JMH 1.37 (`jmh-core`, `jmh-generator-annprocess`).
   - **Controlled Configuration:** `-f 3 -wi 5 -i 8 -p driverCount=100,1000,10000,100000 -p distribution=uniform,clustered`.
   - **Execution Process:** Each revision executed in an isolated fork sequence with a 60-second hardware cooldown between test suites.
   - **Measurement Isolation:** Dataset point generation (`generatePoints`) and tree construction (`buildTree`) take place strictly in `@Setup(Level.Trial)` per fork. The timed method (`quadTreeQueryOnly`) executes queries against the prebuilt tree instance.
2. **Matcher Latency Harness (`MatcherBenchmark.java`):**
   - **Warmup:** 500 untimed invocations per scenario to allow compiler optimization.
   - **Measurement:** 1,000 consecutive timed invocations using `System.nanoTime()`.
   - **Percentile Calculation:** All 1,000 samples sorted in `long[1000]`; exact rank order percentiles computed (p50, p90, p95, p99).

---

## 3. Correctness & Fairness Verification

### 3.1 Pre-Benchmark Correctness Gate vs. Unit Verification
- **JMH Setup Gate (`QuadTreeBenchmark#setUp`):** Runs an automated verification loop across 8 global coordinates (Hyderabad, London, New York, Sydney, antimeridian positive/negative, North and South poles). It verifies that `QuadTree#kNearest`, `fullSort`, and `bruteForceHeap` produce identical sorted distances within $10^{-6}\text{ km}$.
- **Important Distinction:** The JMH pre-benchmark gate verifies **distances only**; it does not assert exact candidate identity for equidistant ties. Exact point identities and deterministic tie-breaking order (by distance, latitude, then longitude) are formally verified by the automated unit test suite (`QuadTreeTest.java`), which asserts exact object equality against brute-force reference lists.
- **Shared Data & Metric:** All implementations evaluate identical datasets generated from fixed random seeds (`Random(42)`) using the same Haversine metric (`GeoPoint#distanceToKm`).

### 3.2 Matcher Correctness Verification
- Verified via `MatcherCorrectnessVerificationTest` across 12 workload scenarios ($R \in \{1, 5, 20, 50\}$, $D \in \{50, 200, 1000\}$):
  - Strictly unique driver/request assignments; atomic claim status verified.
  - AuctionMatcher produced strictly lower total cost than GreedyMatcher in all multi-request batch scenarios ($30.3\%$ to $74.1\%$ lower total cost).

---

## 4. Experiment 1: QuadTree Spatial Performance (Claim 4)

### 4.1 Controlled Benchmark Results (JMH 1.37, 24 Samples per Scenario)
All scores reported in **microseconds per operation (µs/op)**. Lower is better. Baseline commit `374db9e` vs. Optimized commit `de17a2b`.

| Dataset ($N$) | Distribution | Baseline Mean ($\mu_B$) | Baseline Error ($99.9\%$ CI) | Optimized Mean ($\mu_O$) | Optimized Error ($99.9\%$ CI) | Point Speedup ($\mu_B / \mu_O$) | Descriptive Ratio Bounds [Formula 1] |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **100** | Uniform | $18.694\text{ µs}$ | $\pm 1.226\text{ µs}$ | $14.328\text{ µs}$ | $\pm 1.438\text{ µs}$ | **$1.30\times$** | $[1.11\times,\; 1.55\times]$ |
| **100** | Clustered | $28.216\text{ µs}$ | $\pm 1.471\text{ µs}$ | $16.663\text{ µs}$ | $\pm 0.931\text{ µs}$ | **$1.69\times$** | $[1.52\times,\; 1.89\times]$ |
| **1,000** | Uniform | $55.618\text{ µs}$ | $\pm 17.663\text{ µs}$ | $20.697\text{ µs}$ | $\pm 1.398\text{ µs}$ | **$2.69\times$** | $[1.72\times,\; 3.80\times]$ |
| **1,000** | Clustered | $113.625\text{ µs}$ | $\pm 5.336\text{ µs}$ | $45.907\text{ µs}$ | $\pm 2.920\text{ µs}$ | **$2.48\times$** | $[2.22\times,\; 2.77\times]$ |
| **10,000** | Uniform | $301.985\text{ µs}$ | $\pm 12.076\text{ µs}$ | $18.073\text{ µs}$ | $\pm 1.030\text{ µs}$ | **$16.71\times$** | $[15.18\times,\; 18.43\times]$ |
| **10,000** | Clustered | $270.605\text{ µs}$ | $\pm 40.055\text{ µs}$ | $20.410\text{ µs}$ | $\pm 4.404\text{ µs}$ | **$13.26\times$** | $[9.29\times,\; 19.41\times]$ |
| **100,000** | Uniform | $1\,034.413\text{ µs}$ | $\pm 111.565\text{ µs}$ | $31.735\text{ µs}$ | $\pm 1.841\text{ µs}$ | **$32.60\times$** | $[27.49\times,\; 38.33\times]$ |
| **100,000** | Clustered | $908.446\text{ µs}$ | $\pm 37.541\text{ µs}$ | $22.154\text{ µs}$ | $\pm 3.853\text{ µs}$ | **$41.01\times$** | $[33.49\times,\; 51.69\times]$ |

---

### 4.2 Statistical Characterization of Ratio Bounds

The rightmost column in Section 4.1 presents:

$$\text{Descriptive Ratio Bounds} = \left[\frac{\mu_B - \text{CI}_B}{\mu_O + \text{CI}_O},\; \frac{\mu_B + \text{CI}_B}{\mu_O - \text{CI}_O}\right] \quad \text{[Formula 1]}$$

> **Label & Definition:**  
> **"Descriptive ratio bounds obtained from marginal interval extremes; not a formally calibrated confidence interval for speedup."**

#### Methodological Limitations:
1. **Marginal Extreme Bounds vs. Joint Distribution:** Dividing the extreme endpoints of two independent marginal confidence intervals does not yield a calibrated 99.9% confidence interval for the ratio of two random variables ($\mu_B / \mu_O$). Formal ratio estimation requires methods such as Fieller's theorem or the Delta method.
2. **Assumption of Independent and Identically Distributed (IID) Iterations:** JMH computes error margins using Student's $t$-distribution based on the sample standard error across measurement iterations. As demonstrated in Section 4.3, several scenarios exhibit step changes, drift, and multimodal between-fork divergence. These non-stationary patterns make simple IID Gaussian assumptions questionable.
3. **No Uncalculated Statistics:** Methods such as Fieller's theorem, the Delta method, or non-parametric bootstrap ratio intervals have not been calculated in this report and are not presented as computed values.

---

### 4.3 Convergence, Stationarity, and Variability Audit

A read-only audit of the raw JMH logs reveals that measurements are **not uniformly stable across every scenario**, and the observed data does not establish narrowly bounded uncertainty in all cases. Significant intra-fork drift and between-fork divergence were observed in the raw logs:

#### Observed Variability Cases from Raw Logs:
1. **Baseline Uniform, $N=100\,000$, Fork 1 (Intra-Fork Non-Stationarity):**
   - In Fork 1, measurement iterations 1 through 6 ran between $1\,010.58\text{ µs}$ and $1\,173.07\text{ µs}$.
   - Latency then dropped sharply in the final iterations:
     - Iteration 6: `1124.20 µs/op`
     - Iteration 7: `799.86 µs/op`
     - Iteration 8: `484.93 µs/op`
   - *Observation:* A $> 2.3\times$ reduction occurred within a single fork during the measurement window, producing an intra-fork standard deviation of $235.62\text{ µs}$ ($CV = 23.8\%$), while Forks 2 and 3 remained relatively stable at $1\,033.31\text{ µs}$ and $1\,080.49\text{ µs}$.
2. **Optimized Clustered, $N=10\,000$, Fork 2 (Delayed Transition):**
   - In Fork 2, all 5 warmup iterations ran between $8.31\text{ µs}$ and $9.75\text{ µs}$.
   - The first three measurement iterations remained low:
     - Iteration 1: `8.20 µs/op`
     - Iteration 2: `8.05 µs/op`
     - Iteration 3: `8.07 µs/op`
   - Latency then rose sharply:
     - Iteration 4: `16.60 µs/op`
     - Iteration 5: `20.57 µs/op`
     - Iteration 6: `21.05 µs/op`
     - Iteration 7: `23.21 µs/op`
     - Iteration 8: `23.52 µs/op`
   - *Observation:* Across the entire scenario, individual measurements spanned from $8.05\text{ µs}$ to $28.67\text{ µs}$ (a $3.56\times$ spread). Fork 2 shifted regimes mid-measurement, indicating that 5 warmup iterations were not sufficient to reach a single steady state.
3. **Baseline Uniform, $N=1\,000$ (Between-Fork Bimodal Divergence):**
   - Fork 1 mean: `24.27 µs/op` (range: $21.75 - 35.13\text{ µs}$, SD = $4.49\text{ µs}$)
   - Fork 2 mean: `72.57 µs/op` (range: $65.16 - 76.94\text{ µs}$, SD = $3.75\text{ µs}$)
   - Fork 3 mean: `70.01 µs/op` (range: $66.09 - 74.20\text{ µs}$, SD = $3.29\text{ µs}$)
   - *Observation:* Between-fork standard deviation ($27.18\text{ µs}$) was much larger than within-fork variation ($3.29 - 4.49\text{ µs}$). Fork 1 converged to a completely different performance regime than Forks 2 and 3, yielding an overall sample standard deviation of $22.97\text{ µs}$ ($CV = 41.3\%$).

---

### 4.4 Separation of Measured Facts, Interpretations, and Unverified Hypotheses

To maintain rigorous scientific standards, observed empirical facts must not be conflated with unverified causal explanations:

| Category | Description | Specific Audit Items |
| :--- | :--- | :--- |
| **Observed Fact** | Directly recorded in raw JMH logs or verified in source code. | - Baseline uniform $N=100\,000$ Fork 1 dropped from $1\,124.20\text{ µs}$ to $484.93\text{ µs}$ in iterations 6-8.<br>- Optimized clustered $N=10\,000$ Fork 2 ran at $\approx 8\text{ µs}$ for 8 iterations (warmup + measurement) before increasing to $\approx 23.5\text{ µs}$.<br>- Baseline uniform $N=1\,000$ showed bimodal fork means ($24.27\text{ µs}$ vs. $72.57\text{ µs}$ and $70.01\text{ µs}$).<br>- `quadTreeQueryOnly()` executes single-threaded $K=10$ queries against a prebuilt tree with a fixed Hyderabad coordinate.<br>- No JVM crashes or benchmark errors occurred; JMH blackhole compiler mode was active. |
| **Interpretation** | Reasonable deductions directly supported by the observed data. | - The benchmark configurations (5 warmup iterations) did not consistently achieve stationarity prior to measurement in all scenarios.<br>- Standard error calculations based on IID assumptions are affected by the presence of intra-fork drift and multimodal fork divergence.<br>- Despite local instability, the separation between baseline and optimized latencies at $N \ge 10\,000$ is large enough that distributions do not overlap. |
| **Hypothesis** | Possible explanations not verified by diagnostic instrumentation. | - *HotSpot C2 Recompilation / Deoptimization:* Mid-run latency shifts may reflect delayed tier-4 compilation, on-stack replacement (OSR), or branch deoptimization.<br>- *OS Core Scheduling on Hybrid Architecture:* The host Intel Core i7-1360P contains 4 Performance cores and 8 Efficient cores. Bimodal fork execution (e.g. baseline $N=1\,000$) may reflect the OS scheduler placing different forks on P-cores vs. E-cores.<br>- *Garbage Collection / Memory Ergonomics:* Periodic G1GC card table scans or heap region resizing may have caused transient shifts.<br>- *Cache Locality & Memory Subsystem:* Variations in object alignment or hardware prefetcher behavior across trial allocations may contribute to fork differences. |

*(None of the above hypotheses are confirmed facts; verifying them would require dedicated diagnostic profiling with GC logging, compilation traces, and CPU core affinity controls).*

---

### 4.5 Tree Construction Latency (`quadTreeBuildOnly`)
Measured tree construction times across fleet sizes (pre-query setup):
- **$N = 100$:** $2.59\text{ µs}$ (uniform) | $6.37\text{ µs}$ (clustered)
- **$N = 1\,000$:** $38.62\text{ µs}$ (uniform) | $66.16\text{ µs}$ (clustered)
- **$N = 10\,000$:** $1\,645.14\text{ µs}$ (~$1.65\text{ ms}$ uniform) | $1\,683.75\text{ µs}$ (~$1.68\text{ ms}$ clustered)
- **$N = 100\,000$:** $19\,746.08\text{ µs}$ (~$19.75\text{ ms}$ uniform) | $17\,724.52\text{ µs}$ (~$17.72\text{ ms}$ clustered)

---

## 5. Experiment 2: Matcher Latency Workload Matrix (Claim 2)

Measured across 1,000 iterations per scenario. All latencies in **microseconds (µs)**.

| Algorithm | Requests ($R$) | Fleet ($D$) | Matched | Min | **p50** | p90 | p95 | **p99** | Max | Mean | StdDev |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **GreedyMatcher** | 1 | 50 | 1 | 21.2 | **37.4** | 58.5 | 71.1 | **137.9** | 1,440.0 | 41.8 | 58.1 |
| **AuctionMatcher** | 1 | 50 | 1 | 55.6 | **72.0** | 128.7 | 148.9 | **201.6** | 1,061.7 | 84.0 | 45.1 |
| **GreedyMatcher** | 1 | 200 | 1 | 29.7 | **39.5** | 62.1 | 70.8 | **132.0** | 843.3 | 47.5 | 39.0 |
| **AuctionMatcher** | 1 | 200 | 1 | 1,139.8 | **1,619.7** | 2,813.3 | 3,018.6 | **3,722.1** | 6,328.4 | 1,844.5 | 673.1 |
| **GreedyMatcher** | 1 | 1,000 | 1 | 146.0 | **208.6** | 303.9 | 336.7 | **482.3** | 1,382.8 | 233.0 | 75.4 |
| **AuctionMatcher** | 1 | 1,000 | 1 | 17,501.2 | **20,865.9** | 29,899.3 | 33,221.6 | **38,766.4** | 47,209.4 | 22,657.8 | 4,717.0 |
| **GreedyMatcher** | 5 | 50 | 5 | 63.5 | **92.2** | 114.3 | 126.4 | **259.1** | 673.7 | 98.1 | 36.3 |
| **AuctionMatcher** | 5 | 50 | 5 | 182.0 | **211.8** | 321.4 | 390.0 | **487.7** | 738.3 | 235.7 | 69.1 |
| **GreedyMatcher** | 5 | 200 | 5 | 133.1 | **153.1** | 191.0 | 227.9 | **426.2** | 1,097.6 | 165.9 | 59.0 |
| **AuctionMatcher** | 5 | 200 | 5 | 1,832.6 | **2,473.1** | 3,486.9 | 3,852.9 | **4,809.8** | 7,112.2 | 2,620.5 | 641.0 |
| **GreedyMatcher** | 5 | 1,000 | 5 | 1,386.3 | **1,971.9** | 2,568.4 | 2,801.8 | **3,587.9** | 12,188.8 | 2,113.8 | 529.6 |
| **AuctionMatcher** | 5 | 1,000 | 5 | 33,247.0 | **136,709.8** | 157,513.5 | 169,439.2 | **197,491.7** | 227,420.2 | 134,431.8 | 29,643.8 |
| **GreedyMatcher** | 20 | 50 | 20 | 4,314.2 | **7,460.3** | 8,398.0 | 8,723.8 | **9,593.3** | 12,304.1 | 7,626.7 | 624.3 |
| **AuctionMatcher** | 20 | 50 | 20 | 4,808.9 | **25,957.5** | 27,868.0 | 29,009.5 | **33,812.6** | 39,351.1 | 21,791.0 | 8,778.4 |
| **GreedyMatcher** | 20 | 200 | 20 | 7,975.9 | **9,797.8** | 10,901.9 | 11,404.6 | **12,544.9** | 14,454.9 | 10,009.9 | 684.9 |
| **AuctionMatcher** | 20 | 200 | 20 | 20,934.1 | **107,274.5** | 110,300.7 | 112,402.4 | **117,565.3** | 128,894.2 | 98,787.0 | 25,299.8 |
| **GreedyMatcher** | 20 | 1,000 | 20 | 11,232.9 | **13,730.2** | 14,626.9 | 15,396.7 | **16,764.0** | 18,439.9 | 13,878.8 | 736.6 |
| **AuctionMatcher** | 20 | 1,000 | 20 | 137,040.5 | **648,177.0** | 682,153.5 | 698,092.0 | **752,936.4** | 827,373.8 | 576,295.6 | 179,642.7 |
| **GreedyMatcher** | 50 | 50 | 50 | 11,878.0 | **14,329.8** | 15,375.2 | 15,972.1 | **18,602.4** | 29,474.8 | 14,554.8 | 1,013.5 |
| **AuctionMatcher** | 50 | 50 | 50 | 14,537.9 | **75,955.1** | 83,883.2 | 89,020.9 | **104,496.4** | 369,942.0 | 76,443.9 | 18,325.1 |
| **GreedyMatcher** | 50 | 200 | 50 | 17,162.1 | **25,365.7** | 27,107.7 | 28,397.0 | **40,858.7** | 211,782.2 | 26,339.3 | 8,856.3 |
| **AuctionMatcher** | 50 | 200 | 50 | 57,556.4 | **77,753.6** | 312,401.3 | 326,174.0 | **357,639.3** | 856,767.0 | 162,498.8 | 119,257.3 |
| **GreedyMatcher** | 50 | 1,000 | 50 | 6,669.2 | **7,695.8** | 8,539.7 | 8,943.1 | **11,180.0** | 16,983.4 | 7,753.2 | 865.6 |
| **AuctionMatcher** | 50 | 1,000 | 50 | 329,539.8 | **379,100.2** | 488,483.9 | 547,688.5 | **631,101.7** | 773,484.3 | 399,512.9 | 67,067.4 |

---

## 6. Real-System Performance vs. Microbenchmark Boundary

It is essential to maintain a strict separation between isolated microbenchmarks and real-system dispatch performance:

### 6.1 What the Microbenchmark Measures
- `QuadTreeBenchmark#quadTreeQueryOnly()` strictly measures:
  1. An in-memory, single-threaded top-10 nearest-neighbor search ($K = 10$).
  2. One fixed query coordinate (Hyderabad: `17.3850, 78.4867`).
  3. A pre-constructed, static QuadTree instance in memory.
  4. Zero concurrent modifications, network requests, or database operations.

### 6.2 What the Microbenchmark Does NOT Measure
- The microbenchmark does **not** directly measure:
  1. The complete end-to-end dispatch workflow.
  2. Production-shaped queries that may request $K = N$ nearest neighbors.
  3. `AuctionMatcher`'s iterative pricing, bidding, and cost-matrix evaluation loops.
  4. Ingress HTTP latency, Kafka consumer poll batching, Redis driver cache lookups, or PostgreSQL transactional commits.
  5. Multi-threaded system throughput or concurrent write contention.

> [!CAUTION]
> The measured QuadTree speedup ($13\times - 41\times$ at large $N$) cannot be extrapolated to the entire dispatch system. In end-to-end dispatch (where p99 is $\approx 66\text{ ms}$ under load), in-memory spatial search represents a small fraction of the total execution budget; the remainder is governed by network hops, Kafka serialization/polling, and durable database transactions.

---

## 7. Potential Future Experimental Controls

The read-only audit identified variability that could be investigated in future benchmarking if publication-grade stability is required. These are potential experimental controls, **not assumed or guaranteed solutions**:
1. **Extended Warmup Iterations:** Evaluating whether increasing warmup (e.g., `-wi 10 -i 10`) allows HotSpot JIT compilation and speculative branch optimizations to stabilize prior to the timed measurement window.
2. **Diagnostic JVM Tracing:** Running separate diagnostic trials with GC logging (`-Xlog:gc*`) and compilation logging (`-XX:+PrintCompilation`, `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining`) to test whether latency shifts correlate with JVM compiler or GC activity.
3. **Controlled CPU Affinity:** Testing JVM execution pinned to specific Performance cores (P-cores) using OS-level affinity tools to determine whether hybrid architecture core scheduling explains between-fork bimodality.
4. **Explicit JVM Heap Sizing:** Testing fixed heap allocations (e.g., `-Xms4g -Xmx4g`) as an experimental control to isolate the influence of dynamic heap expansion and ergonomics.

*(No source changes, configuration updates, or benchmark reruns were executed for this audit; these items represent prospective controls for future investigation).*

---

## 8. Exact Reproducibility Commands

To reproduce the benchmarks evaluated in this report:

```powershell
# 1. Compile test and JMH generated classes
./mvnw test-compile

# 2. Run Controlled QuadTree JMH Benchmark (Phase D Protocol: 3 forks, 5 warmup, 8 measure)
./mvnw exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=test" `
  "-Dexec.args=-cp %classpath org.openjdk.jmh.Main com.aagosh.dispatch.spatial.QuadTreeBenchmark.quadTreeQueryOnly$ -f 3 -wi 5 -i 8 -p driverCount=100,1000,10000,100000 -p distribution=uniform,clustered"

# 3. Run Matcher Correctness Verification Test
./mvnw test "-Dtest=MatcherCorrectnessVerificationTest"

# 4. Run Matcher Latency Empirical Harness (1,000 samples per scenario)
./mvnw exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=test" `
  "-Dexec.args=-cp %classpath com.aagosh.dispatch.matching.MatcherBenchmark"
```

---

## 9. Corrective Multi-Fork Benchmark: End-to-End Matcher & Construction Verification

Following the initial audit of Phase D results, a corrective multi-fork evaluation was conducted to measure production-shaped query and end-to-end matching performance under strict hardware and JVM controls.

### 9.1 Hierarchy of Evaluated Benchmarks

To avoid ambiguity, three distinct tiers of benchmarks are evaluated in this codebase:

1. **Initial Microbenchmark ($K=10$, Section 4):** Evaluated single-threaded nearest-neighbor traversal on a static prebuilt QuadTree querying a single fixed Hyderabad coordinate (`quadTreeQueryOnly`).
2. **Production-Shaped Spatial Microbenchmark ($K=16$):** Evaluated nearest-neighbor queries with $K=16$ (matching `GreedyMatcher.INITIAL_WINDOW`) cycling across 8 distinct geographic query coordinates on a prebuilt tree with P-core affinity (`quadTreeQuery_productionK16`).
3. **Corrective End-to-End Matcher Benchmark:** Evaluates the complete `GreedyMatcher` lifecycle, including in-memory QuadTree instantiation, driver coordinate insertion, spatial candidate querying, cost evaluation, atomic CAS claim acquisition, and claim release, executed across 18 scenarios in 3 independent JVM forks per scenario.

### 9.2 Controlled Environment & Execution Parameters

- **Hardware Topology & Affinity:** 13th Gen Intel(R) Core(TM) i7-1360P. Process affinity mask `0x00FF` pinned execution strictly to Performance cores (CPUs 0-7, Cores 0-3 with SMT enabled), isolating execution from Efficient cores.
- **JVM Configuration:** OpenJDK 26.0.1+8-34, `-Xms4g -Xmx4g -XX:ActiveProcessorCount=8`.
- **JMH Protocol:** 3 independent JVM forks (`-f 3`), 3 warmup iterations (1s each), 5 timed measurement iterations (1s each) per fork ($N = 15$ timed measurement iterations per scenario).
- **Thermal Control:** 60-second hardware cooldown enforced between baseline (`374db9e`) and optimized (`de17a2b`) test suites.
- **Assignment-Pair Verification:** Every trial match asserted that actual request-to-driver assignment pairs (`requestId -> driverId`) matched probe results exactly. All 18 scenarios matched 100% of requests within the initial $K=16$ spatial window with zero fallback expansions.
- **Raw Evidence Logs (Local Artifacts):**
  The raw execution outputs were saved locally in the evaluation scratch directory:
  - Baseline raw log: `scratch/corrective-baseline.log` (generated under task ID `task-15517`)
  - Optimized raw log: `scratch/corrective-optimized.log` (generated under task ID `task-15527`)
  *Note: These raw logs are local scratch artifacts generated during the audit process and are preserved in the local environment rather than tracked in the Git repository.*

### 9.3 Phase-Level Timing: QuadTree Construction Only

To determine whether commit `de17a2b` altered tree construction overhead, QuadTree construction was benchmarked independently in test scope (`new QuadTree(WORLD_BOUNDS); for (Driver d : drivers) tree.insert(d.location());`) under identical JVM and P-core controls:

| Fleet Size ($D$) | Baseline Construction Mean | Optimized Construction Mean | Point Speedup ($\mu_B / \mu_O$) | Finding |
| :---: | :---: | :---: | :---: | :--- |
| **$D = 200$** | $12.424\text{ µs} \pm 0.424\text{ µs}$ | $12.511\text{ µs} \pm 2.219\text{ µs}$ | **$0.99\times$** | No clear difference observed (overlapping 99.9% error intervals) |
| **$D = 1\,000$** | $100.416\text{ µs} \pm 6.484\text{ µs}$ | $105.537\text{ µs} \pm 8.400\text{ µs}$ | **$0.95\times$** | No clear difference observed (overlapping 99.9% error intervals) |
| **$D = 10,000$** | $1\,617.589\text{ µs} \pm 97.993\text{ µs}$ | $1\,573.137\text{ µs} \pm 250.319\text{ µs}$ | **$1.03\times$** | No clear difference observed ($\approx 1.6\text{ ms}$, overlapping intervals) |

> [!NOTE]
> **Construction Timing Interpretation:** The measured data shows similar means with overlapping reported intervals, supporting the observation that no clear difference in construction overhead was detected between revisions. While this supports the conclusion that construction was not directly optimized, it does not prove that construction costs are invariant or that all end-to-end performance gains arise *exclusively* from traversal; auxiliary interactions (such as memory cache retention or GC pressure) cannot be fully isolated without modifying production internals.

### 9.4 Complete 18-Scenario End-to-End Matcher Results (`GreedyMatcher`)

Scores reflect the complete match-and-release benchmark operation across 15 measurement iterations in 3 independent JVM forks (mean $\pm$ 99.9% CI error margin in µs/op):

| Fleet ($D$) | Reqs ($R$) | Condition | Baseline Mean ($\mu_B$) | Optimized Mean ($\mu_O$) | Point Speedup ($\mu_B / \mu_O$) | Descriptive Ratio Bounds [Formula 1] | $1.0\times$ Overlap |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **200** | **1** | nominal | $45.293\text{ µs} \pm 6.362\text{ µs}$ | $43.637\text{ µs} \pm 3.987\text{ µs}$ | **$1.04\times$** | $[0.82\times,\; 1.30\times]$ | Overlaps $1.0\times$ |
| **200** | **1** | contended | $40.039\text{ µs} \pm 2.967\text{ µs}$ | $35.190\text{ µs} \pm 2.057\text{ µs}$ | **$1.14\times$** | $[1.00\times,\; 1.30\times]$ | Touches $1.00\times$ |
| **200** | **5** | nominal | $198.252\text{ µs} \pm 12.330\text{ µs}$ | $155.842\text{ µs} \pm 20.039\text{ µs}$ | **$1.27\times$** | $[1.06\times,\; 1.55\times]$ | Excludes $1.0\times$ |
| **200** | **5** | contended | $167.098\text{ µs} \pm 12.474\text{ µs}$ | $122.899\text{ µs} \pm 9.110\text{ µs}$ | **$1.36\times$** | $[1.17\times,\; 1.58\times]$ | Excludes $1.0\times$ |
| **200** | **20** | nominal | $3\,033.938\text{ µs} \pm 93.070\text{ µs}$ | $2\,731.783\text{ µs} \pm 216.159\text{ µs}$ | **$1.11\times$** | $[1.00\times,\; 1.24\times]$ | Touches $1.00\times$ |
| **200** | **20** | contended | $1\,739.819\text{ µs} \pm 199.699\text{ µs}$ | $1\,538.228\text{ µs} \pm 72.827\text{ µs}$ | **$1.13\times$** | $[0.96\times,\; 1.32\times]$ | Overlaps $1.0\times$ |
| **1,000** | **1** | nominal | $229.798\text{ µs} \pm 26.867\text{ µs}$ | $197.756\text{ µs} \pm 12.887\text{ µs}$ | **$1.16\times$** | $[0.96\times,\; 1.39\times]$ | Overlaps $1.0\times$ |
| **1,000** | **1** | contended | $227.887\text{ µs} \pm 20.459\text{ µs}$ | $184.069\text{ µs} \pm 13.571\text{ µs}$ | **$1.24\times$** | $[1.05\times,\; 1.46\times]$ | Excludes $1.0\times$ |
| **1,000** | **5** | nominal | $572.072\text{ µs} \pm 65.174\text{ µs}$ | $340.223\text{ µs} \pm 26.938\text{ µs}$ | **$1.68\times$** | $[1.38\times,\; 2.03\times]$ | Excludes $1.0\times$ |
| **1,000** | **5** | contended | $553.255\text{ µs} \pm 52.696\text{ µs}$ | $291.789\text{ µs} \pm 32.683\text{ µs}$ | **$1.90\times$** | $[1.54\times,\; 2.34\times]$ | Excludes $1.0\times$ |
| **1,000** | **20** | nominal | $4\,478.170\text{ µs} \pm 209.735\text{ µs}$ | $3\,225.233\text{ µs} \pm 155.914\text{ µs}$ | **$1.39\times$** | $[1.26\times,\; 1.53\times]$ | Excludes $1.0\times$ |
| **1,000** | **20** | contended | $2\,845.821\text{ µs} \pm 193.304\text{ µs}$ | $1\,953.544\text{ µs} \pm 104.228\text{ µs}$ | **$1.46\times$** | $[1.29\times,\; 1.64\times]$ | Excludes $1.0\times$ |
| **10,000** | **1** | nominal | $2\,915.074\text{ µs} \pm 382.095\text{ µs}$ | $2\,416.618\text{ µs} \pm 197.255\text{ µs}$ | **$1.21\times$** | $[0.97\times,\; 1.49\times]$ | Overlaps $1.0\times$ |
| **10,000** | **1** | contended | $3\,073.320\text{ µs} \pm 242.163\text{ µs}$ | $2\,503.627\text{ µs} \pm 171.577\text{ µs}$ | **$1.23\times$** | $[1.06\times,\; 1.42\times]$ | Excludes $1.0\times$ |
| **10,000** | **5** | nominal | $3\,992.887\text{ µs} \pm 310.068\text{ µs}$ | $2\,207.786\text{ µs} \pm 112.431\text{ µs}$ | **$1.81\times$** | $[1.59\times,\; 2.05\times]$ | Excludes $1.0\times$ |
| **10,000** | **5** | contended | $3\,945.122\text{ µs} \pm 273.208\text{ µs}$ | $2\,756.120\text{ µs} \pm 418.699\text{ µs}$ | **$1.43\times$** | $[1.16\times,\; 1.80\times]$ | Excludes $1.0\times$ |
| **10,000** | **20** | nominal | $10\,045.327\text{ µs} \pm 1\,509.541\text{ µs}$ | $5\,265.201\text{ µs} \pm 568.180\text{ µs}$ | **$1.91\times$** | $[1.46\times,\; 2.46\times]$ | Excludes $1.0\times$ |
| **10,000** | **20** | contended | $8\,126.975\text{ µs} \pm 785.596\text{ µs}$ | $4\,738.060\text{ µs} \pm 341.362\text{ µs}$ | **$1.72\times$** | $[1.45\times,\; 2.03\times]$ | Excludes $1.0\times$ |

### 9.5 Key Findings & Critical Boundaries

1. **Resolution of Single-Process Artifacts:** In an earlier exploratory run without per-scenario JVM isolation, scenarios at $D=10,000$ exhibited performance regressions when executed after 18,000 continuous unisolated iterations. Under independent JVM forks (`-f 3`), the optimized revision demonstrated point speedups across all 18 scenarios ($1.04\times$ to $1.91\times$), resolving the earlier artifact.
2. **Characterization of Statistical Uncertainty:** Six scenarios total have descriptive bounds that do not establish a statistically conclusive speedup: four scenarios overlap $1.0\times$ ($D=200, R=1$ nominal; $D=200, R=20$ contended; $D=1,000, R=1$ nominal; $D=10,000, R=1$ nominal) and two touch $1.00\times$ ($D=200, R=1$ contended; $D=200, R=20$ nominal). The remaining 12 scenarios strictly exclude $1.0\times$, with gains concentrated in multi-request batches ($R \ge 5$) and larger fleets ($D \ge 1,000$). Because these ratio intervals are descriptive bounds derived from separate JMH confidence intervals, they must not be interpreted as formal significance tests.
3. **Complete Match-and-Release Lifecycle Scope:** Because assignment claims were cleared inside the measured benchmark method to support repeated invocation, the reported figures reflect the complete match-and-release lifecycle (including QuadTree instantiation, driver coordinate insertion, spatial candidate querying, cost evaluation, atomic CAS claim acquisition, and claim release), rather than isolated `match()` return latency.
4. **Production System Applicability:** The production dispatch system's default engine is `AuctionMatcher` (dense bipartite cost matrix optimization), which does not use `QuadTree`. These benchmark results apply strictly to `GreedyMatcher` and spatial nearest-neighbor operations, and do not imply performance changes for the default `AuctionMatcher` dispatch path.

### 9.6 Execution Protocol & Reproduction Guide

> [!IMPORTANT]
> **Reproducibility Limitations & Local Audit Artifact Prerequisites:**
> 1. **Local Benchmark Harness Required:** The benchmark class `GreedyMatcherCorrectiveBenchmark.java` is an uncommitted local audit artifact developed specifically for this multi-fork evaluation. It is **not present in a clean Git clone** of either baseline (`374db9e`) or optimized (`de17a2b`). An independent reviewer cannot retrieve the harness from Git repository contents alone; reviewers must obtain the exact harness source separately and place it at `src/test/java/com/aagosh/dispatch/matching/GreedyMatcherCorrectiveBenchmark.java` in both worktrees prior to compilation.
> 2. **Historical Raw Logs Are Local Artifacts:** The historical raw log files (`scratch/corrective-baseline.log` and `scratch/corrective-optimized.log`) are local scratch files produced on the audit workstation during tasks `task-15517` and `task-15527`. They are uncommitted local artifacts and are not available from a clean clone.
> 3. **Proposed Experiment Repetition vs. Verification from Repository:** The reproduction procedure below is a **proposed way to repeat the experiment** in a controlled Windows environment, not a way to retrieve or independently verify the original historical measurements from tracked repository contents alone.

> [!NOTE]
> **Measurement Scope vs. Untimed Trial Setup:**
> - **`greedyMatch`:** The timed benchmark method executes the complete match-and-release lifecycle: in-memory QuadTree instantiation, driver coordinate insertion, spatial candidate querying ($K=16$), cost matrix evaluation, atomic CAS driver claim acquisition, and claim release.
> - **`quadTreeConstructionOnly`:** The timed benchmark method strictly isolates QuadTree instantiation and driver coordinate insertion (`new QuadTree(WORLD_BOUNDS); for (Driver d : drivers) tree.insert(d.location())`).
> - **`@Setup(Level.Trial)` (Untimed):** Prior to timed measurement iterations, the setup method executes a full probe match and asserts 100% request-to-driver assignment pair matching against expected results. This verification runs outside the timed measurement iterations to validate input datasets without skewing latency.

#### Windows-Specific CPU Affinity & HotSpot Configuration

- **Topology & Mask Selection:** On the evaluation system (13th Gen Intel(R) Core(TM) i7-1360P, 12 physical cores, 16 logical processors), Cores 0-3 are Performance cores with SMT enabled, mapped to logical CPUs 0-7 (affinity bitmask `0x00FF`). Cores 4-11 are Efficient cores without SMT (logical CPUs 8-15, bitmask `0xFF00`). The mask `0x00FF` confines execution strictly to P-cores.
- **Affinity Inheritance Mechanism:** Affinity is set on the launching PowerShell process via `[System.Diagnostics.Process]::GetCurrentProcess().ProcessorAffinity = [IntPtr]0x00FF`. On Windows, child processes created via Win32 `CreateProcess` inherit the parent's process affinity mask by default. Preflight tests (`test_affinity.ps1`, `TestProcessBuilderAffinity.java`) confirmed that child PowerShell processes and Java `ProcessBuilder` children inherited `0x00FF`.
- **Inheritance vs. Per-Fork Verification:** While preflight diagnostics confirmed process-tree inheritance, the individual JMH forked worker JVMs were not monitored by a continuous out-of-band affinity profiler during the benchmark execution. Affinity enforcement across the forked JVMs relies on Win32 parent-child process inheritance.
- **Active Processor Count:** Passing `-XX:ActiveProcessorCount=8` inside `-jvmArgs` informs the HotSpot runtime that 8 processors are available, sizing HotSpot's internal thread pools (GC worker threads, JIT compiler threads, `ForkJoinPool.commonPool()`) to the 8 logical processors of the Performance cores, rather than the 16 threads of the entire physical package.
- **Processor Count Preflight Limitation:** The preflight check `[Environment]::ProcessorCount -ge 8` establishes solely a threshold on total logical processors to prevent Win32 out-of-range CPU index exceptions when applying `0x00FF`. It does not inspect physical core topology or prove that logical CPUs 0-7 correspond to Performance cores on an arbitrary host. Reviewers on different hardware topologies must verify their core layout or adjust/remove the affinity mask accordingly.
- **Platform Limitation:** This process-level affinity configuration is Windows PowerShell-specific. It relies on Win32 process inheritance and does not apply to other operating systems without platform-specific pinning tools.

#### JMH Method Selection & Direct Process Execution Architecture

To avoid fragile multi-shell nesting, the proposed reproduction procedure eliminates intermediate child PowerShell sessions entirely:
- **Direct Process Execution:** The reproduction script runs each benchmark target individually via `cmd.exe /d /c` directly invoking `mvnw.cmd` inside the designated worktree directory (`WorkingDirectory = $WorktreePath`).
- **JMH Regex Anchors (`$`):** JMH benchmark selection arguments are regular expressions. Trailing `$` anchors in `greedyMatch$` and `quadTreeConstructionOnly$` strictly match the end of the method name, preventing prefix collisions with other methods. In outer PowerShell, `$BenchmarkSelector` is passed as a verbatim single-quoted string literal (e.g. `'com.aagosh...greedyMatch$'`), completely avoiding variable interpolation or backtick escaping collisions in PowerShell.
- **Argument Forwarding & Quote Preservation:** The command string passed to `cmd.exe /d /c` is enclosed in an outer pair of quotes (`cmd.exe /d /c ""%CMD%" %ARGS% 2>&1"`). Under standard Win32 `cmd.exe` parsing rules (`cmd.exe /?` rule 2), `cmd.exe` strips the outer boundary quotes and forwards the interior command and its double-quoted arguments verbatim. Maven's `-Dexec.args` preserves `-cp %classpath` and the unbroken single-quoted `-jvmArgs '-Xms4g -Xmx4g -XX:ActiveProcessorCount=8'` string without nested shell expansion.
- **Independent Exit-Code Detection & Sequence Halting:** Rather than string-chaining multiple commands, each benchmark target is executed as an independent step. The Win32 process exit code (`$proc.ExitCode`) is captured directly from `System.Diagnostics.Process`. If any benchmark step returns a non-zero exit code, the parent PowerShell script halts immediately with an exception, preventing subsequent benchmark targets, thermal cooldown, and subsequent worktree runs from executing.
- **Affinity Inheritance & Caller Restoration:** At Step 0, the script sets `[System.Diagnostics.Process]::GetCurrentProcess().ProcessorAffinity = [IntPtr]0x00FF` on the host PowerShell session. In Win32, all child processes (`cmd.exe`, `mvnw.cmd`, `java.exe`, and forked JMH worker JVMs) inherit this process affinity mask automatically upon creation. The entire execution block is enclosed in a `try ... finally` block that restores the caller's original affinity mask upon script completion or error.
- **Single-Pipe Stream Merging & Resource Disposal:** Native OS stderr is redirected into stdout at the `cmd.exe` invocation level (`2>&1`), streaming a single combined pipe line-by-line to both the console and the log file without multi-pipe deadlock risk. In the `finally` block, both the `System.IO.StreamWriter` and `System.Diagnostics.Process` instances are explicitly checked, flushed, closed, and disposed, preventing resource or handle leaks on exceptional exit paths.

#### Audit Execution History vs. Proposed Reviewer Reproduction Procedure

**1. Historical Audit Invocations (Recorded):**
In the recorded audit run, isolated temporary worktrees were used for each revision. Within each worktree, both benchmark methods were executed sequentially in a single chained PowerShell command with P-core affinity:
- **Baseline Run (`task-15517`):** Executed in `C:\Aagosh\CODE\JAVA\dispatch-baseline-exp2-corrected` (commit `374db9e2510d023be208ef314a482d99c928f7ce`):
  ```powershell
  powershell -Command "[System.Diagnostics.Process]::GetCurrentProcess().ProcessorAffinity = [IntPtr]0x00FF; Set-Location 'C:\Aagosh\CODE\JAVA\dispatch-baseline-exp2-corrected'; .\mvnw.cmd exec:exec '-Dexec.executable=java' '-Dexec.classpathScope=test' '-Dexec.args=-cp %classpath org.openjdk.jmh.Main com.aagosh.dispatch.matching.GreedyMatcherCorrectiveBenchmark.greedyMatch$ -f 3 -wi 3 -i 5 -p driverCount=200,1000,10000 -p requestCount=1,5,20 -p condition=nominal,contended -jvmArgs ''-Xms4g -Xmx4g -XX:ActiveProcessorCount=8'''; .\mvnw.cmd exec:exec '-Dexec.executable=java' '-Dexec.classpathScope=test' '-Dexec.args=-cp %classpath org.openjdk.jmh.Main com.aagosh.dispatch.matching.GreedyMatcherCorrectiveBenchmark.quadTreeConstructionOnly$ -f 3 -wi 3 -i 5 -p driverCount=200,1000,10000 -p requestCount=1 -p condition=nominal -jvmArgs ''-Xms4g -Xmx4g -XX:ActiveProcessorCount=8'''"
  ```
- **Thermal Cooldown:** An automated 60-second idle period was enforced between runs.
- **Optimized Run (`task-15527`):** Executed in `C:\Aagosh\CODE\JAVA\dispatch-optimized-exp2-corrected` (commit `de17a2ba54536cc92776ffe104e0e7e1e353ab2b`):
  ```powershell
  powershell -Command "[System.Diagnostics.Process]::GetCurrentProcess().ProcessorAffinity = [IntPtr]0x00FF; Set-Location 'C:\Aagosh\CODE\JAVA\dispatch-optimized-exp2-corrected'; .\mvnw.cmd exec:exec '-Dexec.executable=java' '-Dexec.classpathScope=test' '-Dexec.args=-cp %classpath org.openjdk.jmh.Main com.aagosh.dispatch.matching.GreedyMatcherCorrectiveBenchmark.greedyMatch$ -f 3 -wi 3 -i 5 -p driverCount=200,1000,10000 -p requestCount=1,5,20 -p condition=nominal,contended -jvmArgs ''-Xms4g -Xmx4g -XX:ActiveProcessorCount=8'''; .\mvnw.cmd exec:exec '-Dexec.executable=java' '-Dexec.classpathScope=test' '-Dexec.args=-cp %classpath org.openjdk.jmh.Main com.aagosh.dispatch.matching.GreedyMatcherCorrectiveBenchmark.quadTreeConstructionOnly$ -f 3 -wi 3 -i 5 -p driverCount=200,1000,10000 -p requestCount=1 -p condition=nominal -jvmArgs ''-Xms4g -Xmx4g -XX:ActiveProcessorCount=8'''"
  ```

**2. Proposed Robust Reproduction Procedure for Reviewers:**
To re-run this experiment in an independent environment, execute the following PowerShell script from the repository root. This proposed procedure:
- Intended to support **Windows PowerShell 5.1** and **PowerShell 7+** on 64-bit Windows, using standard `New-Object -TypeName ... -ArgumentList @(...)` constructor parameter syntax; note that full automated reproduction across both versions has not been runtime certified.
- Dynamically derives and validates the Git worktree root using Git commands (rejecting silent fallbacks to hard-coded paths), while documenting machine-specific path and CPU topology assumptions.
- Validates prerequisites (Git worktree status, target commit presence, worktree destination path availability, harness existence, diagnostic log preservation policy, and a host logical processor count threshold preventing out-of-range affinity index exceptions) before creating any worktrees.
- Implements robust partial-failure handling during worktree creation and harness deployment, halting immediately and reporting surviving worktrees for manual inspection/cleanup.
- Enforces explicit child-process exit-code capture via `System.Diagnostics.Process` independent of the output logging pipeline, halting immediately upon failure so a failed benchmark cannot run cooldown or subsequent targets.
- Redirects native OS stderr into stdout at the child-process level (`cmd.exe /d /c ... 2>&1`), reading a single combined stream line-by-line to EOF. This preserves the stream sequence as observed on that pipe without multi-pipe deadlocks, while recognizing that independently buffered runtime output (such as JVM block-buffered stdout vs. unbuffered stderr) reflects the order in which buffers are flushed to the OS rather than physical simultaneity.
- Preserves combined stdout and stderr in separate diagnostic logs located in the repository root.
- Uses safe directory navigation (`Push-Location` / `Pop-Location` within `try` / `finally`).
- Decouples worktree cleanup into a manual-only post-audit step, ensuring that worktrees and logs are never automatically removed on failure.

```powershell
# Stop on unhandled script exceptions
$ErrorActionPreference = 'Stop'

# Helper function: Execute a single benchmark target with direct Win32 exit-code capture,
# single-pipe handle redirection (cmd.exe /d /c ... 2>&1), and robust resource disposal.
# Intended PowerShell Environments: Windows PowerShell 5.1 or PowerShell 7+ on 64-bit Windows.
# Note: Syntactically designed for Windows PowerShell 5.1 and PowerShell 7+, but full end-to-end execution
# of this reproduction suite has not been automated or certified across both runtime versions.
#
# Native OS stderr (handle 2) is redirected into stdout (handle 1) at the child-process level via
# 'cmd.exe /d /c ... 2>&1'. This merges shell, Maven, and JVM stdout and stderr into a single
# combined stream across the OS pipe, avoiding multi-pipe buffer deadlocks and eliminating
# deferred trailing stderr. This guarantees output is read in the exact order observed on that pipe, but
# does not guarantee the wall-clock order of output events that are buffered independently by user-space runtimes.
function Invoke-BenchmarkStep {
    param(
        [Parameter(Mandatory=$true)][string]$WorktreePath,
        [Parameter(Mandatory=$true)][string]$BenchmarkSelector,
        [Parameter(Mandatory=$true)][string]$LogPath
    )

    $psi = New-Object -TypeName System.Diagnostics.ProcessStartInfo
    $psi.FileName = "cmd.exe"
    $psi.WorkingDirectory = $WorktreePath

    # Invoke mvnw.cmd directly via cmd.exe /d /c, redirecting stderr to stdout (2>&1).
    # The entire command line is enclosed in outer quotes per standard cmd.exe quote-stripping rules.
    $mvnwCmd = ".\mvnw.cmd"
    $innerCmd = "$mvnwCmd exec:exec `"-Dexec.executable=java`" `"-Dexec.classpathScope=test`" `"-Dexec.args=-cp %classpath org.openjdk.jmh.Main $BenchmarkSelector -f 3 -wi 3 -i 5 -p driverCount=200,1000,10000 -p requestCount=1,5,20 -p condition=nominal,contended -jvmArgs '-Xms4g -Xmx4g -XX:ActiveProcessorCount=8'`" 2>&1"
    $psi.Arguments = "/d /c `"$innerCmd`""
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.CreateNoWindow = $true

    $proc = $null
    $writer = $null

    try {
        # Append to log file so multiple sequential benchmark targets are preserved in the same log
        $writer = New-Object -TypeName System.IO.StreamWriter -ArgumentList @($LogPath, $true, [System.Text.Encoding]::UTF8)
        $proc = New-Object -TypeName System.Diagnostics.Process
        $proc.StartInfo = $psi

        $started = $proc.Start()
        if (-not $started) {
            throw "Failed to start benchmark process for selector '$BenchmarkSelector' in '$WorktreePath'."
        }

        # Synchronously stream the single combined stream line-by-line to both console and log in real time
        while ($null -ne ($line = $proc.StandardOutput.ReadLine())) {
            [Console]::Out.WriteLine($line)
            $writer.WriteLine($line)
        }

        # Wait for the child process to exit
        $proc.WaitForExit()
        return $proc.ExitCode
    } finally {
        if ($null -ne $writer) {
            $writer.Flush()
            $writer.Close()
            $writer.Dispose()
        }
        if ($null -ne $proc) {
            $proc.Close()
            $proc.Dispose()
        }
    }
}

# Step 0: Environment Detection, Path Configuration, & Machine-Specific Assumptions
# Intended PowerShell Versions: Windows PowerShell 5.1 or PowerShell 7+ on 64-bit Windows.
# This reproduction script assumes a 13th Gen Intel CPU topology where Performance cores reside
# on logical CPUs 0-7 (affinity mask 0x00FF).
# Repository root is dynamically derived via Git; hard-coded path fallbacks are intentionally avoided.
$repoRootRaw = (git rev-parse --show-toplevel 2>$null)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($repoRootRaw)) {
    throw "Preflight validation failed: Unable to determine Git repository root via 'git rev-parse --show-toplevel'. Ensure Git is installed and execute this script from within the dispatch repository."
}
$repoRoot = $repoRootRaw.Trim().Replace('/', '\')

# Validate worktree root using Git commands (compatible with both main repositories and linked worktrees)
$isWorkTree = (git -C $repoRoot rev-parse --is-inside-work-tree 2>$null)
if ($LASTEXITCODE -ne 0 -or $isWorkTree.Trim() -ne "true") {
    throw "Preflight validation failed: '$repoRoot' is not recognized as a valid Git worktree by 'git rev-parse --is-inside-work-tree'."
}

# Define worktree paths and commits
$baselinePath = "C:\Aagosh\CODE\JAVA\dispatch-baseline"
$optimizedPath = "C:\Aagosh\CODE\JAVA\dispatch-optimized"
$baselineCommit = "374db9e2510d023be208ef314a482d99c928f7ce"
$optimizedCommit = "de17a2ba54536cc92776ffe104e0e7e1e353ab2b"

# Define log paths (stored outside temporary worktrees in repository root)
$baselineLog = "$repoRoot\reproduction-baseline.log"
$optimizedLog = "$repoRoot\reproduction-optimized.log"

# Define benchmark harness source path
$harnessRelativePath = "src\test\java\com\aagosh\dispatch\matching\GreedyMatcherCorrectiveBenchmark.java"
$harnessSource = "$repoRoot\$harnessRelativePath"

# Step 1: Preflight Prerequisites Validation (Fail early before touching worktrees or logs)
Write-Host "Validating reproduction prerequisites..."

# 1a. Validate target commits exist in repository
git -C $repoRoot cat-file -e "$baselineCommit^{commit}" 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "Preflight failed: Baseline commit '$baselineCommit' does not exist in repository '$repoRoot'."
}
git -C $repoRoot cat-file -e "$optimizedCommit^{commit}" 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "Preflight failed: Optimized commit '$optimizedCommit' does not exist in repository '$repoRoot'."
}

# 1b. Validate benchmark harness availability
if (-not (Test-Path $harnessSource)) {
    throw "Preflight failed: Benchmark harness not found at '$harnessSource'. Because GreedyMatcherCorrectiveBenchmark.java is an uncommitted local audit artifact, reviewers must provide this source file in the repository before executing reproduction."
}

# 1c. Validate worktree destination paths are not already occupied
if (Test-Path $baselinePath) {
    throw "Preflight failed: Baseline worktree destination '$baselinePath' already exists. Remove or relocate the existing directory before running."
}
if (Test-Path $optimizedPath) {
    throw "Preflight failed: Optimized worktree destination '$optimizedPath' already exists. Remove or relocate the existing directory before running."
}

# 1d. Validate log preservation policy (do not overwrite existing diagnostic evidence)
if (Test-Path $baselineLog) {
    throw "Preflight failed: Existing diagnostic log found at '$baselineLog'. To protect recorded evidence against accidental overwrite, archive, rename, or remove the existing log before starting."
}
if (Test-Path $optimizedLog) {
    throw "Preflight failed: Existing diagnostic log found at '$optimizedLog'. To protect recorded evidence against accidental overwrite, archive, rename, or remove the existing log before starting."
}

# 1e. Validate host CPU logical processor count threshold for affinity mask 0x00FF
# IMPORTANT LIMITATION: This check verifies ONLY that the host has at least 8 logical processors
# so that Win32 SetProcessAffinityMask does not throw an out-of-range CPU index exception.
# It does NOT verify that logical CPUs 0-7 are Performance cores, nor does it inspect physical
# core topology. The 0x00FF bitmask reflects the specific hardware topology of the audit
# workstation (Intel Core i7-1360P: 4 P-cores / 8 threads on CPUs 0-7). On systems with different
# core counts or non-hybrid topologies, adjust or remove the affinity mask accordingly.
$logicalProcessorCount = [Environment]::ProcessorCount
if ($logicalProcessorCount -lt 8) {
    throw "Preflight failed: Host has $logicalProcessorCount logical processors, but the P-core affinity mask 0x00FF requires at least 8 logical processors (CPUs 0-7). On systems with fewer than 8 logical processors, adjust the affinity mask or remove affinity pinning."
}

# Preserve caller process affinity and apply P-core mask 0x00FF
# All child processes (cmd.exe, mvnw.cmd, java.exe, JMH worker forks) inherit this mask via Win32 process inheritance.
$originalAffinity = [System.Diagnostics.Process]::GetCurrentProcess().ProcessorAffinity
[System.Diagnostics.Process]::GetCurrentProcess().ProcessorAffinity = [IntPtr]0x00FF

try {
    # Step A: Create isolated worktrees for baseline and optimized revisions
    # Handles partial failure: if baseline worktree succeeds but optimized creation fails,
    # report the surviving worktree and intentionally leave it intact for inspection/cleanup.
    Write-Host "Creating isolated worktrees..."
    git worktree add $baselinePath $baselineCommit
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create baseline worktree at '$baselinePath' (exit code $LASTEXITCODE). No worktrees were created."
    }

    git worktree add $optimizedPath $optimizedCommit
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create optimized worktree at '$optimizedPath' (exit code $LASTEXITCODE). Partial worktree creation detected: the surviving baseline worktree at '$baselinePath' has been intentionally left intact for manual inspection/cleanup. Remove it manually with: git worktree remove '$baselinePath' --force"
    }

    # Step B: Copy benchmark harness into both worktrees (guarded with explicit error handling)
    Write-Host "Deploying benchmark harness to worktrees..."
    try {
        Copy-Item -Path $harnessSource -Destination "$baselinePath\$harnessRelativePath" -Force -ErrorAction Stop
    } catch {
        throw "Failed to copy benchmark harness to baseline worktree at '$baselinePath\$harnessRelativePath': $($_.Exception.Message). Surviving worktrees at '$baselinePath' and '$optimizedPath' have been intentionally left intact for manual inspection/cleanup."
    }

    try {
        Copy-Item -Path $harnessSource -Destination "$optimizedPath\$harnessRelativePath" -Force -ErrorAction Stop
    } catch {
        throw "Failed to copy benchmark harness to optimized worktree at '$optimizedPath\$harnessRelativePath': $($_.Exception.Message). Surviving worktrees at '$baselinePath' and '$optimizedPath' have been intentionally left intact for manual inspection/cleanup."
    }

    # Step C: Compile test classes in baseline worktree
    Write-Host "Compiling baseline test classes..."
    Push-Location $baselinePath
    try {
        .\mvnw.cmd test-compile
        if ($LASTEXITCODE -ne 0) { throw "Baseline compilation failed with exit code $LASTEXITCODE. Surviving worktrees at '$baselinePath' and '$optimizedPath' have been intentionally left intact for manual inspection." }
    } finally {
        Pop-Location
    }

    # Step D: Compile test classes in optimized worktree
    Write-Host "Compiling optimized test classes..."
    Push-Location $optimizedPath
    try {
        .\mvnw.cmd test-compile
        if ($LASTEXITCODE -ne 0) { throw "Optimized compilation failed with exit code $LASTEXITCODE. Surviving worktrees at '$baselinePath' and '$optimizedPath' have been intentionally left intact for manual inspection." }
    } finally {
        Pop-Location
    }

    # Step E: Run Baseline Benchmark Suite with P-Core Affinity (0x00FF)
    # Output is concurrently streamed to console and preserved to $baselineLog.
    # Each target is executed independently with explicit exit code verification.
    Write-Host "Executing baseline benchmark (1/2: greedyMatch)..."
    $exitE1 = Invoke-BenchmarkStep -WorktreePath $baselinePath -BenchmarkSelector 'com.aagosh.dispatch.matching.GreedyMatcherCorrectiveBenchmark.greedyMatch$' -LogPath $baselineLog
    if ($exitE1 -ne 0) {
        throw "Baseline greedyMatch benchmark run failed with child process exit code $exitE1. Halting execution; quadTreeConstructionOnly, cooldown, and optimized suite will not run. Surviving worktrees at '$baselinePath' and '$optimizedPath' have been intentionally left intact for manual inspection. Inspect diagnostic log at: $baselineLog"
    }

    Write-Host "Executing baseline benchmark (2/2: quadTreeConstructionOnly)..."
    $exitE2 = Invoke-BenchmarkStep -WorktreePath $baselinePath -BenchmarkSelector 'com.aagosh.dispatch.matching.GreedyMatcherCorrectiveBenchmark.quadTreeConstructionOnly$' -LogPath $baselineLog
    if ($exitE2 -ne 0) {
        throw "Baseline quadTreeConstructionOnly benchmark run failed with child process exit code $exitE2. Halting execution; cooldown and optimized suite will not run. Surviving worktrees at '$baselinePath' and '$optimizedPath' have been intentionally left intact for manual inspection. Inspect diagnostic log at: $baselineLog"
    }

    # Step F: Enforce 60-second thermal cooldown
    Write-Host "Baseline benchmark complete. Enforcing 60-second thermal cooldown..."
    Start-Sleep -Seconds 60

    # Step G: Run Optimized Benchmark Suite with P-Core Affinity (0x00FF)
    # Output is concurrently streamed to console and preserved to $optimizedLog.
    # Each target is executed independently with explicit exit code verification.
    Write-Host "Executing optimized benchmark (1/2: greedyMatch)..."
    $exitG1 = Invoke-BenchmarkStep -WorktreePath $optimizedPath -BenchmarkSelector 'com.aagosh.dispatch.matching.GreedyMatcherCorrectiveBenchmark.greedyMatch$' -LogPath $optimizedLog
    if ($exitG1 -ne 0) {
        throw "Optimized greedyMatch benchmark run failed with child process exit code $exitG1. Halting execution; quadTreeConstructionOnly will not run. Surviving worktrees at '$baselinePath' and '$optimizedPath' have been intentionally left intact for manual inspection. Inspect diagnostic log at: $optimizedLog"
    }

    Write-Host "Executing optimized benchmark (2/2: quadTreeConstructionOnly)..."
    $exitG2 = Invoke-BenchmarkStep -WorktreePath $optimizedPath -BenchmarkSelector 'com.aagosh.dispatch.matching.GreedyMatcherCorrectiveBenchmark.quadTreeConstructionOnly$' -LogPath $optimizedLog
    if ($exitG2 -ne 0) {
        throw "Optimized quadTreeConstructionOnly benchmark run failed with child process exit code $exitG2. Halting execution. Surviving worktrees at '$baselinePath' and '$optimizedPath' have been intentionally left intact for manual inspection. Inspect diagnostic log at: $optimizedLog"
    }

    Write-Host "Reproduction suite completed successfully."
    Write-Host "Baseline log preserved at: $baselineLog"
    Write-Host "Optimized log preserved at: $optimizedLog"
} finally {
    # Restore original caller process affinity mask
    [System.Diagnostics.Process]::GetCurrentProcess().ProcessorAffinity = $originalAffinity
}

# Step H: Manual Worktree Cleanup (Explicit Post-Audit Action)
# By design, this script NEVER automatically removes worktrees on success or failure.
# This guarantees worktree files and compiled classes remain accessible for post-run inspection.
# Once logs and output have been verified, execute cleanup manually:
#   git worktree remove C:\Aagosh\CODE\JAVA\dispatch-baseline --force
#   git worktree remove C:\Aagosh\CODE\JAVA\dispatch-optimized --force
```
