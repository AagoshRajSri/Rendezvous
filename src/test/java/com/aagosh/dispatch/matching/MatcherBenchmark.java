package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.RideRequest;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmark for measuring pure dispatch matching execution latency across
 * {@link AuctionMatcher} (Bertsekas Auction Algorithm) and {@link GreedyMatcher}
 * (Spatial QuadTree Greedy Matcher) with {@link DispatchCostFunction}.
 *
 * <p>Can be executed via JMH:
 * <pre>
 *   org.openjdk.jmh.Main com.aagosh.dispatch.matching.MatcherBenchmark
 * </pre>
 * or directly as a standalone percentile measurement harness:
 * <pre>
 *   java com.aagosh.dispatch.matching.MatcherBenchmark
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Thread)
public class MatcherBenchmark {

    @Param({"1", "5", "20", "50"})
    public int requestCount;

    @Param({"50", "200", "1000"})
    public int driverCount;

    private List<RideRequest> requests;
    private List<Driver> drivers;
    private DispatchCostFunction costFunction;
    private AuctionMatcher auctionMatcher;
    private GreedyMatcher greedyMatcher;

    @Setup(Level.Trial)
    public void setUp() {
        auctionMatcher = new AuctionMatcher();
        greedyMatcher = new GreedyMatcher();

        Random random = new Random(42);
        double centerLat = 40.7128;
        double centerLon = -74.0060;

        List<Driver> generatedDrivers = new ArrayList<>(driverCount);
        for (int i = 0; i < driverCount; i++) {
            double lat = centerLat + (random.nextGaussian() * 0.05);
            double lon = centerLon + (random.nextGaussian() * 0.05);
            generatedDrivers.add(new Driver("d-" + i, new GeoPoint(lat, lon), new DriverState()));
        }
        drivers = List.copyOf(generatedDrivers);

        List<RideRequest> generatedRequests = new ArrayList<>(requestCount);
        for (int i = 0; i < requestCount; i++) {
            double lat = centerLat + (random.nextGaussian() * 0.05);
            double lon = centerLon + (random.nextGaussian() * 0.05);
            generatedRequests.add(new RideRequest("req-" + i, new GeoPoint(lat, lon)));
        }
        requests = List.copyOf(generatedRequests);

        costFunction = new DispatchCostFunction(DispatchCostConfig.DEFAULT, requests);
    }

    @Benchmark
    public List<Assignment> auctionMatch() {
        List<Assignment> assignments = auctionMatcher.match(requests, drivers, costFunction);
        for (Assignment a : assignments) {
            a.driver().state().releaseClaim(a.request().id());
        }
        return assignments;
    }

    @Benchmark
    public List<Assignment> greedyMatch() {
        List<Assignment> assignments = greedyMatcher.match(requests, drivers, costFunction);
        for (Assignment a : assignments) {
            a.driver().state().releaseClaim(a.request().id());
        }
        return assignments;
    }

    // =========================================================================
    // Standalone High-Precision Percentile Measurement Runner
    // =========================================================================

    public record BenchmarkResult(
            String algorithm,
            int requests,
            int drivers,
            int matchedCount,
            double minUs,
            double p50Us,
            double p90Us,
            double p95Us,
            double p99Us,
            double maxUs,
            double meanUs,
            double stdDevUs
    ) {}

    public static void main(String[] args) {
        System.out.println("=== Rendezvous Matcher Latency Benchmark Harness ===");
        System.out.println("JVM: " + System.getProperty("java.version") + " (" + System.getProperty("java.vm.name") + ")");
        System.out.println("Cores: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Warmup: 500 iterations per scenario | Measurement: 1,000 iterations per scenario\n");

        int[] requestCounts = {1, 5, 20, 50};
        int[] driverCounts = {50, 200, 1000};

        AuctionMatcher auctionMatcher = new AuctionMatcher();
        GreedyMatcher greedyMatcher = new GreedyMatcher();

        List<BenchmarkResult> results = new ArrayList<>();

        for (int R : requestCounts) {
            for (int D : driverCounts) {
                // Generate test data
                Random random = new Random(42);
                double centerLat = 40.7128;
                double centerLon = -74.0060;

                List<Driver> drivers = new ArrayList<>(D);
                for (int i = 0; i < D; i++) {
                    double lat = centerLat + (random.nextGaussian() * 0.05);
                    double lon = centerLon + (random.nextGaussian() * 0.05);
                    drivers.add(new Driver("d-" + i, new GeoPoint(lat, lon), new DriverState()));
                }
                drivers = List.copyOf(drivers);

                List<RideRequest> requests = new ArrayList<>(R);
                for (int i = 0; i < R; i++) {
                    double lat = centerLat + (random.nextGaussian() * 0.05);
                    double lon = centerLon + (random.nextGaussian() * 0.05);
                    requests.add(new RideRequest("req-" + i, new GeoPoint(lat, lon)));
                }
                requests = List.copyOf(requests);

                DispatchCostFunction costFn = new DispatchCostFunction(DispatchCostConfig.DEFAULT, requests);

                // Benchmark AuctionMatcher
                BenchmarkResult auctionRes = runMeasurement(
                        "AuctionMatcher (Bertsekas)",
                        auctionMatcher,
                        requests,
                        drivers,
                        costFn,
                        500,
                        1000
                );
                results.add(auctionRes);

                // Benchmark GreedyMatcher
                BenchmarkResult greedyRes = runMeasurement(
                        "GreedyMatcher (Spatial QuadTree)",
                        greedyMatcher,
                        requests,
                        drivers,
                        costFn,
                        500,
                        1000
                );
                results.add(greedyRes);

                System.out.printf("Completed: R=%d, D=%d -> Auction p50=%.2f us, Greedy p50=%.2f us%n",
                        R, D, auctionRes.p50Us(), greedyRes.p50Us());
            }
        }

        printMarkdownTable(results);
    }

    private static BenchmarkResult runMeasurement(
            String algorithmName,
            Matcher matcher,
            List<RideRequest> requests,
            List<Driver> drivers,
            CostFunction costFunction,
            int warmupIters,
            int measureIters
    ) {
        // Warmup JIT C2 compiler
        for (int w = 0; w < warmupIters; w++) {
            List<Assignment> assignments = matcher.match(requests, drivers, costFunction);
            for (Assignment a : assignments) {
                a.driver().state().releaseClaim(a.request().id());
            }
        }

        long[] durationsNanos = new long[measureIters];
        int matchedCount = 0;

        // Timed measurement loop
        for (int i = 0; i < measureIters; i++) {
            long start = System.nanoTime();
            List<Assignment> assignments = matcher.match(requests, drivers, costFunction);
            long end = System.nanoTime();
            durationsNanos[i] = (end - start);

            if (i == 0) {
                matchedCount = assignments.size();
            }

            // Clean up claims outside the measurement window
            for (Assignment a : assignments) {
                a.driver().state().releaseClaim(a.request().id());
            }
        }

        Arrays.sort(durationsNanos);

        double minUs = durationsNanos[0] / 1000.0;
        double p50Us = durationsNanos[(int) (measureIters * 0.50)] / 1000.0;
        double p90Us = durationsNanos[(int) (measureIters * 0.90)] / 1000.0;
        double p95Us = durationsNanos[(int) (measureIters * 0.95)] / 1000.0;
        double p99Us = durationsNanos[(int) (measureIters * 0.99)] / 1000.0;
        double maxUs = durationsNanos[measureIters - 1] / 1000.0;

        double sum = 0;
        for (long d : durationsNanos) {
            sum += d / 1000.0;
        }
        double meanUs = sum / measureIters;

        double varSum = 0;
        for (long d : durationsNanos) {
            double diff = (d / 1000.0) - meanUs;
            varSum += diff * diff;
        }
        double stdDevUs = Math.sqrt(varSum / measureIters);

        return new BenchmarkResult(
                algorithmName,
                requests.size(),
                drivers.size(),
                matchedCount,
                minUs,
                p50Us,
                p90Us,
                p95Us,
                p99Us,
                maxUs,
                meanUs,
                stdDevUs
        );
    }

    private static void printMarkdownTable(List<BenchmarkResult> results) {
        System.out.println("\n### Matcher Latency Measurement Results (Microseconds)\n");
        System.out.println("| Algorithm | Requests (R) | Fleet (D) | Matched | Min (µs) | p50 (µs) | p90 (µs) | p95 (µs) | p99 (µs) | Max (µs) | Mean (µs) | StdDev (µs) |");
        System.out.println("| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |");

        for (BenchmarkResult r : results) {
            System.out.printf("| %s | %d | %d | %d | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f |%n",
                    r.algorithm(),
                    r.requests(),
                    r.drivers(),
                    r.matchedCount(),
                    r.minUs(),
                    r.p50Us(),
                    r.p90Us(),
                    r.p95Us(),
                    r.p99Us(),
                    r.maxUs(),
                    r.meanUs(),
                    r.stdDevUs()
            );
        }
        System.out.println();
    }
}
