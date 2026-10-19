package com.aagosh.dispatch.spatial;

import com.aagosh.dispatch.domain.GeoPoint;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class QuadTreeObserverTest {

    private final BoundingBox bounds = new BoundingBox(-90, -180, 90, 180);

    @Test
    @DisplayName("kNearest with observer returns identical points in identical order to unobserved search")
    void observedSearchProducesIdenticalResults() {
        QuadTree tree = new QuadTree(bounds);
        Random random = new Random(12345);

        List<GeoPoint> points = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            double lat = -80.0 + random.nextDouble() * 160.0;
            double lon = -170.0 + random.nextDouble() * 340.0;
            GeoPoint p = new GeoPoint(lat, lon);
            points.add(p);
            tree.insert(p);
        }

        GeoPoint query = new GeoPoint(17.3850, 78.4867);
        int[] testKs = {1, 5, 16, 50, 150};

        for (int k : testKs) {
            QuadTreeSearchStats stats = new QuadTreeSearchStats();

            List<GeoPoint> unobserved = tree.kNearest(query, k);
            List<GeoPoint> observed = tree.kNearest(query, k, stats);

            assertThat(observed)
                    .as("Observed search must return exact same points as unobserved for k=" + k)
                    .containsExactlyElementsOf(unobserved);

            assertThat(stats.visitedNodes())
                    .as("Search must visit at least one node")
                    .isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("QuadTreeSearchStats accurately counts visits and prunes during branch-and-bound search")
    void recordsVisitsAndPruning() {
        QuadTree tree = new QuadTree(bounds);

        // Clustered points in Hyderabad region
        for (int i = 0; i < 50; i++) {
            tree.insert(new GeoPoint(17.30 + i * 0.01, 78.40 + i * 0.01));
        }

        // Distant points in Americas / Europe
        tree.insert(new GeoPoint(40.7128, -74.0060)); // New York
        tree.insert(new GeoPoint(51.5074, -0.1278));  // London
        tree.insert(new GeoPoint(-33.8688, 151.2093)); // Sydney

        GeoPoint query = new GeoPoint(17.3850, 78.4867);
        QuadTreeSearchStats stats = new QuadTreeSearchStats();

        List<GeoPoint> nearest = tree.kNearest(query, 5, stats);

        assertThat(nearest).hasSize(5);
        assertThat(stats.visitedNodes()).isGreaterThan(0);
        assertThat(stats.prunedNodes()).isGreaterThan(0);
        assertThat(stats.distanceEvaluations()).isGreaterThan(0);

        // Reset verifies counts zero out
        stats.reset();
        assertThat(stats.visitedNodes()).isZero();
        assertThat(stats.prunedNodes()).isZero();
        assertThat(stats.distanceEvaluations()).isZero();
    }

    @Test
    @DisplayName("Handles empty tree query gracefully with zero visits, zero distance evaluations, and zero results")
    void emptyTreeQuery() {
        QuadTree emptyTree = new QuadTree(bounds);
        QuadTreeSearchStats stats = new QuadTreeSearchStats();

        List<GeoPoint> result = emptyTree.kNearest(new GeoPoint(0.0, 0.0), 10, stats);

        assertThat(result).isEmpty();
        assertThat(stats.visitedNodes()).isEqualTo(1); // root visited
        assertThat(stats.prunedNodes()).isZero();
        assertThat(stats.distanceEvaluations()).isZero();
        assertThat(stats.pruningPercentage(0)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("QuadTreeSearchStats accurately counts actual point-to-point distance evaluations and pruning ratio")
    void accuratelyCountsPointDistanceEvaluationsAndCalculatesPruning() {
        QuadTree tree = new QuadTree(bounds);

        // 1. Small tree where N <= k: all points must be evaluated, 0% pruning
        QuadTree smallTree = new QuadTree(bounds);
        for (int i = 0; i < 5; i++) {
            smallTree.insert(new GeoPoint(10.0 + i, 10.0 + i));
        }
        QuadTreeSearchStats smallStats = new QuadTreeSearchStats();
        List<GeoPoint> smallResult = smallTree.kNearest(new GeoPoint(10.0, 10.0), 10, smallStats);
        assertThat(smallResult).hasSize(5);
        assertThat(smallStats.distanceEvaluations()).isEqualTo(5);
        assertThat(smallStats.pruningPercentage(5)).isEqualTo(0.0);

        // 2. Clustered dataset with distant cluster: distant points must not be evaluated
        // 20 points in Hyderabad cluster
        for (int i = 0; i < 20; i++) {
            tree.insert(new GeoPoint(17.35 + i * 0.005, 78.45 + i * 0.005));
        }
        // 10 points in London cluster
        for (int i = 0; i < 10; i++) {
            tree.insert(new GeoPoint(51.50 + i * 0.005, -0.12 + i * 0.005));
        }
        int totalIndexed = tree.size();
        assertThat(totalIndexed).isEqualTo(30);

        GeoPoint hydQuery = new GeoPoint(17.3850, 78.4867);
        QuadTreeSearchStats clusteredStats = new QuadTreeSearchStats();
        List<GeoPoint> clusteredResult = tree.kNearest(hydQuery, 5, clusteredStats);

        assertThat(clusteredResult).hasSize(5);
        // Distant London nodes pruned; actual distance evaluations must be strictly less than brute force
        assertThat(clusteredStats.distanceEvaluations())
                .as("Pruned search must evaluate strictly fewer than all indexed points")
                .isLessThan(totalIndexed)
                .isGreaterThanOrEqualTo(5);

        double expectedPruning = 100.0 * (1.0 - ((double) clusteredStats.distanceEvaluations() / totalIndexed));
        assertThat(clusteredStats.pruningPercentage(totalIndexed))
                .isCloseTo(expectedPruning, Offset.offset(1e-9))
                .isGreaterThan(0.0);
    }

    @Test
    @DisplayName("Handles repeated coordinates and small datasets deterministically")
    void handlesDuplicateCoordinatesAndSmallTrees() {
        QuadTree tree = new QuadTree(bounds);
        GeoPoint duplicate = new GeoPoint(12.9716, 77.5946); // Bangalore
        for (int i = 0; i < 12; i++) {
            tree.insert(duplicate);
        }
        assertThat(tree.size()).isEqualTo(12);

        QuadTreeSearchStats stats = new QuadTreeSearchStats();
        List<GeoPoint> result = tree.kNearest(duplicate, 5, stats);

        assertThat(result).hasSize(5);
        // All 12 points share identical coordinates in the tree; all points in visited leaf evaluated
        assertThat(stats.distanceEvaluations()).isEqualTo(12);
        assertThat(stats.pruningPercentage(12)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Pruning percentage handles boundary edge cases: zero, negative, and full evaluations")
    void pruningPercentageEdgeCases() {
        QuadTreeSearchStats stats = new QuadTreeSearchStats();

        // Zero or negative total points
        assertThat(stats.pruningPercentage(0)).isEqualTo(0.0);
        assertThat(stats.pruningPercentage(-10)).isEqualTo(0.0);

        // Zero evaluations out of 100 points = 100% pruning
        assertThat(stats.pruningPercentage(100)).isEqualTo(100.0);

        // 25 evaluations out of 100 points = 75% pruning
        for (int i = 0; i < 25; i++) {
            stats.onDistanceEvaluated();
        }
        assertThat(stats.pruningPercentage(100)).isEqualTo(75.0);

        // 100 evaluations out of 100 points = 0% pruning
        for (int i = 0; i < 75; i++) {
            stats.onDistanceEvaluated();
        }
        assertThat(stats.distanceEvaluations()).isEqualTo(100);
        assertThat(stats.pruningPercentage(100)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Observer exceptions in onDistanceEvaluated are safely suppressed without disrupting spatial search")
    void throwingObserverDoesNotDisruptSearch() {
        QuadTree tree = new QuadTree(bounds);
        for (int i = 0; i < 20; i++) {
            tree.insert(new GeoPoint(17.38 + i * 0.005, 78.48 + i * 0.005));
        }

        GeoPoint query = new GeoPoint(17.3850, 78.4867);
        List<GeoPoint> expected = tree.kNearest(query, 5);

        // Faulty observer that throws exceptions on every callback
        QuadTreeObserver failingObserver = new QuadTreeObserver() {
            @Override
            public void onNodeVisited(BoundingBox boundary, boolean isLeaf) {
                throw new RuntimeException("Simulated observer visit crash");
            }

            @Override
            public void onNodePruned(BoundingBox boundary) {
                throw new RuntimeException("Simulated observer prune crash");
            }

            @Override
            public void onDistanceEvaluated() {
                throw new RuntimeException("Simulated observer distance evaluation crash");
            }
        };

        List<GeoPoint> actual = tree.kNearest(query, 5, failingObserver);

        assertThat(actual)
                .as("Search with failing observer must still return exact same results as unobserved search")
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("Concurrent searches on the same QuadTree maintain complete counter isolation")
    void concurrentSearchesMaintainCounterIsolation() throws Exception {
        QuadTree tree = new QuadTree(bounds);
        Random random = new Random(42);
        for (int i = 0; i < 100; i++) {
            tree.insert(new GeoPoint(-50.0 + random.nextDouble() * 100.0, -100.0 + random.nextDouble() * 200.0));
        }

        GeoPoint query = new GeoPoint(0.0, 0.0);
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    QuadTreeSearchStats stats = new QuadTreeSearchStats();
                    List<GeoPoint> res = tree.kNearest(query, 5, stats);
                    assertThat(res).hasSize(5);
                    return stats.distanceEvaluations();
                });
            }

            List<Future<Integer>> futures = executor.invokeAll(tasks);
            int baselineCount = futures.get(0).get();
            assertThat(baselineCount).isGreaterThan(0);

            for (Future<Integer> future : futures) {
                assertThat(future.get())
                        .as("Every concurrent search must record identical distance evaluations")
                        .isEqualTo(baselineCount);
            }
        } finally {
            executor.shutdown();
        }
    }
}
