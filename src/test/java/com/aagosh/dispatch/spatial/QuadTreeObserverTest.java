package com.aagosh.dispatch.spatial;

import com.aagosh.dispatch.domain.GeoPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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

        // Reset verifies counts zero out
        stats.reset();
        assertThat(stats.visitedNodes()).isZero();
        assertThat(stats.prunedNodes()).isZero();
    }

    @Test
    @DisplayName("Handles empty tree query gracefully with zero visits and zero results")
    void emptyTreeQuery() {
        QuadTree emptyTree = new QuadTree(bounds);
        QuadTreeSearchStats stats = new QuadTreeSearchStats();

        List<GeoPoint> result = emptyTree.kNearest(new GeoPoint(0.0, 0.0), 10, stats);

        assertThat(result).isEmpty();
        assertThat(stats.visitedNodes()).isEqualTo(1); // root visited
        assertThat(stats.prunedNodes()).isZero();
    }

    @Test
    @DisplayName("Observer exceptions are safely suppressed without disrupting spatial search or altering results")
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
        };

        List<GeoPoint> actual = tree.kNearest(query, 5, failingObserver);

        assertThat(actual)
                .as("Search with failing observer must still return exact same results as unobserved search")
                .containsExactlyElementsOf(expected);
    }
}
