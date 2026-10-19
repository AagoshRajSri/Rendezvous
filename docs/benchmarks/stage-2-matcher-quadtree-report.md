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

> The optimized QuadTree showed substantially lower observed mean latency in the tested top-10 nearest-neighbor microbenchmarks, particularly for datasets containing 10,000–100,000 points. Several scenarios exhibited iteration-level or fork-level variability, so the reported speedups are descriptive measurements rather than formally calibrated ratio confidence intervals. These results do not establish equivalent gains in end-to-end dispatch performance.

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
| **Observed Fact** | Directly recorded in raw JMH logs or verified in source code. | - Baseline uniform $N=100\,000$ Fork 1 dropped from $1\,124.20\text{ µs}$ to $484.93\text{ µs}$ in iterations 6–8.<br>- Optimized clustered $N=10\,000$ Fork 2 ran at $\approx 8\text{ µs}$ for 8 iterations (warmup + measurement) before increasing to $\approx 23.5\text{ µs}$.<br>- Baseline uniform $N=1\,000$ showed bimodal fork means ($24.27\text{ µs}$ vs. $72.57\text{ µs}$ and $70.01\text{ µs}$).<br>- `quadTreeQueryOnly()` executes single-threaded $K=10$ queries against a prebuilt tree with a fixed Hyderabad coordinate.<br>- No JVM crashes or benchmark errors occurred; JMH blackhole compiler mode was active. |
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

