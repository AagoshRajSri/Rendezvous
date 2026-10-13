
package com.aagosh.dispatch.spatial;

import com.aagosh.dispatch.domain.GeoPoint;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(3)
@State(Scope.Thread)
public class QuadTreeBenchmark {

    // K used by the original top-K benchmarks (retained for comparison).
    private static final int K_SMALL = 10;
    private static final double DISTANCE_TOLERANCE_KM = 1e-6;

    @Param({"100", "1000", "10000", "100000"})
    public int driverCount;

    @Param({"uniform", "clustered"})
    public String distribution;

    private List<GeoPoint> points;
    private List<GeoPoint> validationQueries;

    // Index into validationQueries, advanced per benchmark invocation to
    // cycle through all 8 query locations and avoid JIT constant-folding.
    private int queryIndex;

    private BoundingBox boundary;
    private QuadTree preparedTree;

    private record Candidate(GeoPoint point, double distanceKm) {}

    private static final Comparator<Candidate> NEAREST_FIRST =
            Comparator.comparingDouble(Candidate::distanceKm)
                    .thenComparingDouble(c -> c.point().latitude())
                    .thenComparingDouble(c -> c.point().longitude());

    @Setup(Level.Trial)
    public void setUp() {
        points = generatePoints(driverCount, distribution);
        boundary = new BoundingBox(-90, -180, 90, 180);
        queryIndex = 0;

        validationQueries = List.of(
                new GeoPoint(17.3850, 78.4867),   // Hyderabad
                new GeoPoint(51.5072, -0.1276),   // London
                new GeoPoint(40.7128, -74.0060),  // New York
                new GeoPoint(-33.8688, 151.2093), // Sydney
                new GeoPoint(0.0, 179.8),         // Near antimeridian
                new GeoPoint(0.0, -179.8),        // Other side of antimeridian
                new GeoPoint(89.0, 45.0),         // Near north pole
                new GeoPoint(-89.0, -45.0)        // Near south pole
        );

        preparedTree = buildTree();

        // Correctness gate: every algorithm must agree on the nearest K_SMALL
        // distances at all 8 query locations before timing begins.
        int kAll = points.size(); // production-shaped K
        for (GeoPoint query : validationQueries) {
            List<GeoPoint> expected       = fullSort(query, K_SMALL);
            List<GeoPoint> heapResult     = bruteForceHeap(query, K_SMALL);
            List<GeoPoint> treeResult     = preparedTree.kNearest(query, K_SMALL);

            assertSameNearestDistances(query, expected, heapResult,
                    "Brute-force heap (K_SMALL)");
            assertSameNearestDistances(query, expected, treeResult,
                    "QuadTree (K_SMALL)");

            // Also validate production-K paths against full sort.
            List<GeoPoint> expectedAll    = fullSort(query, kAll);
            List<GeoPoint> fullSortAllRes = fullSortAll(query);
            List<GeoPoint> treeAllResult  = preparedTree.kNearest(query, kAll);

            assertSameNearestDistances(query, expectedAll, fullSortAllRes,
                    "Full sort all");
            assertSameNearestDistances(query, expectedAll, treeAllResult,
                    "QuadTree (K_ALL)");
        }
    }

    // -----------------------------------------------------------------------
    // Data generation helpers
    // -----------------------------------------------------------------------

    private List<GeoPoint> generatePoints(int count, String kind) {
        Random random = new Random(42);

        List<GeoPoint> generated = new ArrayList<>(count);

        double[][] clusterCenters = {
                {17.3850, 78.4867},
                {51.5072, -0.1276},
                {40.7128, -74.0060},
                {-33.8688, 151.2093}
        };

        for (int i = 0; i < count; i++) {
            double latitude;
            double longitude;

            if ("clustered".equals(kind)) {
                double[] center =
                        clusterCenters[random.nextInt(clusterCenters.length)];

                latitude = clamp(
                        center[0] + random.nextGaussian() * 1.5,
                        -89.9,
                        89.9
                );

                longitude = normalizeLongitude(
                        center[1] + random.nextGaussian() * 1.5
                );
            } else {
                latitude  = -89.9 + random.nextDouble() * 179.8;
                longitude = -180.0 + random.nextDouble() * 360.0;
            }

            generated.add(new GeoPoint(latitude, longitude));
        }

        return List.copyOf(generated);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double normalizeLongitude(double longitude) {
        return ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
    }

    private QuadTree buildTree() {
        QuadTree tree = new QuadTree(boundary);

        for (GeoPoint point : points) {
            if (!tree.insert(point)) {
                throw new IllegalStateException(
                        "Generated point was outside the tree boundary: " + point
                );
            }
        }

        if (tree.size() != points.size()) {
            throw new IllegalStateException(
                    "Tree size differs from generated point count"
            );
        }

        return tree;
    }

    // Returns the next query location, cycling through all 8.
    // Called once per benchmark invocation so the JIT sees varying queries.
    private GeoPoint nextQuery() {
        GeoPoint q = validationQueries.get(queryIndex % validationQueries.size());
        queryIndex++;
        return q;
    }

    // -----------------------------------------------------------------------
    // Baseline algorithms
    // -----------------------------------------------------------------------

    // Baseline 1: compute every distance then sort — top-K variant.
    private List<GeoPoint> fullSort(GeoPoint query, int k) {
        List<Candidate> candidates = new ArrayList<>(points.size());

        for (GeoPoint point : points) {
            candidates.add(new Candidate(
                    point,
                    query.distanceToKm(point)
            ));
        }

        candidates.sort(NEAREST_FIRST);

        return candidates.stream()
                .limit(k)
                .map(Candidate::point)
                .toList();
    }

    // Baseline 2: compute every distance then sort — all points.
    // Represents the cheapest correct algorithm when K = N.
    private List<GeoPoint> fullSortAll(GeoPoint query) {
        List<Candidate> candidates = new ArrayList<>(points.size());

        for (GeoPoint point : points) {
            candidates.add(new Candidate(
                    point,
                    query.distanceToKm(point)
            ));
        }

        candidates.sort(NEAREST_FIRST);

        return candidates.stream()
                .map(Candidate::point)
                .toList();
    }

    // Baseline 3: scan every point but keep only the nearest K candidates.
    private List<GeoPoint> bruteForceHeap(GeoPoint query, int k) {
        PriorityQueue<Candidate> nearest = new PriorityQueue<>(
                k,
                NEAREST_FIRST.reversed()
        );

        for (GeoPoint point : points) {
            Candidate candidate = new Candidate(
                    point,
                    query.distanceToKm(point)
            );

            if (nearest.size() < k) {
                nearest.offer(candidate);
            } else if (NEAREST_FIRST.compare(
                    candidate, nearest.peek()) < 0) {
                nearest.poll();
                nearest.offer(candidate);
            }
        }

        List<Candidate> result = new ArrayList<>(nearest);
        result.sort(NEAREST_FIRST);

        return result.stream()
                .map(Candidate::point)
                .toList();
    }

    // -----------------------------------------------------------------------
    // Correctness helper
    // -----------------------------------------------------------------------

    private void assertSameNearestDistances(
            GeoPoint query,
            List<GeoPoint> expected,
            List<GeoPoint> actual,
            String algorithm
    ) {
        if (expected.size() != actual.size()) {
            throw new IllegalStateException(
                    algorithm + " returned " + actual.size()
                            + " results; expected " + expected.size()
            );
        }

        List<Double> expectedDistances = expected.stream()
                .map(query::distanceToKm)
                .sorted()
                .toList();

        List<Double> actualDistances = actual.stream()
                .map(query::distanceToKm)
                .sorted()
                .toList();

        for (int i = 0; i < expectedDistances.size(); i++) {
            double difference = Math.abs(
                    expectedDistances.get(i) - actualDistances.get(i)
            );

            if (difference > DISTANCE_TOLERANCE_KM) {
                throw new IllegalStateException(
                        algorithm + " disagrees with full-sort reference"
                                + " at rank " + i
                                + "; expected distance="
                                + expectedDistances.get(i)
                                + " km, actual distance="
                                + actualDistances.get(i)
                                + " km"
                );
            }
        }
    }

    // -----------------------------------------------------------------------
    // Retained original benchmarks (top-K = 10, fixed query) — unchanged
    // -----------------------------------------------------------------------

    @Benchmark
    public List<GeoPoint> fullSortNearest() {
        return fullSort(validationQueries.get(0), K_SMALL);
    }

    @Benchmark
    public List<GeoPoint> bruteForceTopKHeap() {
        return bruteForceHeap(validationQueries.get(0), K_SMALL);
    }

    @Benchmark
    public List<GeoPoint> quadTreeQueryOnly() {
        return preparedTree.kNearest(validationQueries.get(0), K_SMALL);
    }

    @Benchmark
    public QuadTree quadTreeBuildOnly() {
        return buildTree();
    }

    @Benchmark
    public List<GeoPoint> quadTreeBuildAndQuery() {
        QuadTree tree = buildTree();
        return tree.kNearest(validationQueries.get(0), K_SMALL);
    }

    // -----------------------------------------------------------------------
    // New production-shaped benchmarks (K = N, cycled query locations)
    // -----------------------------------------------------------------------

    /**
     * Production-shaped baseline: compute all N distances, sort completely.
     * This is the cheapest correct algorithm when K = N.
     * Query location cycles through all 8 validation points.
     */
    @Benchmark
    public List<GeoPoint> fullSortAll_productionK() {
        return fullSortAll(nextQuery());
    }

    /**
     * Production-shaped QuadTree query: K = number of indexed points.
     * This mirrors GreedyMatcher's actual call: kNearest(pickup, driversByLocation.size()).
     * Query location cycles through all 8 validation points.
     */
    @Benchmark
    public List<GeoPoint> quadTreeQueryOnly_productionK() {
        return preparedTree.kNearest(nextQuery(), points.size());
    }

    /**
     * Production-shaped end-to-end: build tree then query with K = N.
     * Query location cycles through all 8 validation points.
     */
    @Benchmark
    public List<GeoPoint> quadTreeBuildAndQuery_productionK() {
        QuadTree tree = buildTree();
        return tree.kNearest(nextQuery(), points.size());
    }
}
