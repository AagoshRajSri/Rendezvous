package com.aagosh.dispatch.spatial;

import com.aagosh.dispatch.domain.GeoPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproducible measurement harness for QuadTree point-to-point distance calculations
 * and pruning percentages across the benchmark configurations.
 */
public class QuadTreeDistanceMeasurementHarness {

    private static final int SEED = 42;
    private static final int K = 10;

    public record QueryMeasurement(
            String queryName,
            GeoPoint query,
            int distanceEvaluations,
            double pruningPercentage
    ) {}

    public record ScenarioResult(
            int driverCount,
            String distribution,
            int totalIndexedPoints,
            List<QueryMeasurement> queryMeasurements,
            double avgDistanceEvaluations,
            double avgPruningPercentage
    ) {}

    public static final List<NamedQuery> VALIDATION_QUERIES = List.of(
            new NamedQuery("Hyderabad", new GeoPoint(17.3850, 78.4867)),
            new NamedQuery("London", new GeoPoint(51.5072, -0.1276)),
            new NamedQuery("New York", new GeoPoint(40.7128, -74.0060)),
            new NamedQuery("Sydney", new GeoPoint(-33.8688, 151.2093)),
            new NamedQuery("Near Antimeridian (East)", new GeoPoint(0.0, 179.8)),
            new NamedQuery("Near Antimeridian (West)", new GeoPoint(0.0, -179.8)),
            new NamedQuery("Near North Pole", new GeoPoint(89.0, 45.0)),
            new NamedQuery("Near South Pole", new GeoPoint(-89.0, -45.0))
    );

    public record NamedQuery(String name, GeoPoint point) {}

    @Test
    @DisplayName("Run reproducible distance evaluation and pruning measurement across all configurations")
    void measureAllScenarios() {
        int[] counts = {100, 1000, 10000, 100000};
        String[] distributions = {"uniform", "clustered"};

        System.out.println("=========================================================================================");
        System.out.println("QUADTREE DISTANCE EVALUATION & PRUNING BASELINE MEASUREMENT REPORT");
        System.out.println("=========================================================================================");

        for (String dist : distributions) {
            for (int count : counts) {
                ScenarioResult result = evaluateScenario(count, dist);

                System.out.printf("%n### Configuration: N = %d, Distribution = %s, Seed = %d, K = %d, Total Points = %d%n",
                        result.driverCount(), result.distribution(), SEED, K, result.totalIndexedPoints());
                System.out.println("| Query Name | Coordinates | Distance Evaluations | Brute Force (N) | Pruning % |");
                System.out.println("| :--- | :--- | :---: | :---: | :---: |");

                for (QueryMeasurement m : result.queryMeasurements()) {
                    System.out.printf("| %s | (%.4f, %.4f) | %d | %d | %.2f%% |%n",
                            m.queryName(), m.query().latitude(), m.query().longitude(),
                            m.distanceEvaluations(), result.totalIndexedPoints(), m.pruningPercentage());
                }

                System.out.printf("**Average Distance Evaluations:** %.1f (out of %d)%n",
                        result.avgDistanceEvaluations(), result.totalIndexedPoints());
                System.out.printf("**Average Pruning Percentage:** %.2f%%%n",
                        result.avgPruningPercentage());

                assertThat(result.totalIndexedPoints()).isEqualTo(count);
                assertThat(result.avgDistanceEvaluations()).isGreaterThan(0.0);
            }
        }
        System.out.println("=========================================================================================");
    }

    public static ScenarioResult evaluateScenario(int count, String distribution) {
        List<GeoPoint> points = generatePoints(count, distribution, SEED);
        BoundingBox boundary = new BoundingBox(-90, -180, 90, 180);
        QuadTree tree = new QuadTree(boundary);

        for (GeoPoint p : points) {
            tree.insert(p);
        }

        List<QueryMeasurement> measurements = new ArrayList<>();
        double totalEvaluations = 0;
        double totalPruning = 0;

        for (NamedQuery nq : VALIDATION_QUERIES) {
            QuadTreeSearchStats stats = new QuadTreeSearchStats();
            List<GeoPoint> results = tree.kNearest(nq.point(), K, stats);

            // Correctness check: compare distances with brute-force reference
            List<GeoPoint> expected = fullSort(points, nq.point(), K);
            assertThat(results).hasSize(Math.min(K, count));
            for (int i = 0; i < results.size(); i++) {
                double expectedDist = nq.point().distanceToKm(expected.get(i));
                double actualDist = nq.point().distanceToKm(results.get(i));
                assertThat(actualDist).isCloseTo(expectedDist, org.assertj.core.data.Offset.offset(1e-6));
            }

            int evals = stats.distanceEvaluations();
            double pruning = stats.pruningPercentage(points.size());

            measurements.add(new QueryMeasurement(nq.name(), nq.point(), evals, pruning));
            totalEvaluations += evals;
            totalPruning += pruning;
        }

        return new ScenarioResult(
                count,
                distribution,
                points.size(),
                measurements,
                totalEvaluations / VALIDATION_QUERIES.size(),
                totalPruning / VALIDATION_QUERIES.size()
        );
    }

    private static List<GeoPoint> generatePoints(int count, String kind, int seed) {
        Random random = new Random(seed);
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
                double[] center = clusterCenters[random.nextInt(clusterCenters.length)];
                latitude = clamp(center[0] + random.nextGaussian() * 1.5, -89.9, 89.9);
                longitude = normalizeLongitude(center[1] + random.nextGaussian() * 1.5);
            } else {
                latitude = -89.9 + random.nextDouble() * 179.8;
                longitude = -180.0 + random.nextDouble() * 360.0;
            }

            generated.add(new GeoPoint(latitude, longitude));
        }

        return List.copyOf(generated);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double normalizeLongitude(double longitude) {
        return ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
    }

    private record Candidate(GeoPoint point, double distanceKm) {}

    private static final Comparator<Candidate> NEAREST_FIRST =
            Comparator.comparingDouble(Candidate::distanceKm)
                    .thenComparingDouble(c -> c.point().latitude())
                    .thenComparingDouble(c -> c.point().longitude());

    private static List<GeoPoint> fullSort(List<GeoPoint> points, GeoPoint query, int k) {
        List<Candidate> candidates = new ArrayList<>(points.size());
        for (GeoPoint point : points) {
            candidates.add(new Candidate(point, query.distanceToKm(point)));
        }
        candidates.sort(NEAREST_FIRST);
        return candidates.stream().limit(k).map(Candidate::point).toList();
    }
}
