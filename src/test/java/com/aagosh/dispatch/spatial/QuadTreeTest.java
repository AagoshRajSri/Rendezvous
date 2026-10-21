
package com.aagosh.dispatch.spatial;

import com.aagosh.dispatch.domain.GeoPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class QuadTreeTest {

    private void assertNearestMatchesBruteForce(
            QuadTree tree,
            List<GeoPoint> points,
            GeoPoint query,
            int k
    ) {
        List<GeoPoint> expected = points.stream()
                .sorted(Comparator.comparingDouble((GeoPoint p) -> query.distanceToKm(p))
                        .thenComparingDouble(GeoPoint::latitude)
                        .thenComparingDouble(GeoPoint::longitude))
                .limit(k)
                .toList();

        List<GeoPoint> actual = tree.kNearest(query, k);

        assertEquals(expected.size(), actual.size());

        for (int i = 0; i < expected.size(); i++) {
            assertEquals(
                    query.distanceToKm(expected.get(i)),
                    query.distanceToKm(actual.get(i)),
                    1e-8,
                    "Nearest distance differs at index " + i
                            + " for query " + query
            );
            assertEquals(
                    expected.get(i),
                    actual.get(i),
                    "Nearest point identity differs at index " + i
                            + " for query " + query
            );
        }
    }

    private BoundingBox createBoundary() {
        return new BoundingBox(-90, -180, 90, 180);
    }

    @Test
    void randomizedNearestResultsMatchBruteForce() {
        Random random = new Random(42);

        BoundingBox bounds =
                new BoundingBox(-80, -170, 80, 170);

        for (int trial = 0; trial < 100; trial++) {
            QuadTree tree = new QuadTree(bounds);
            List<GeoPoint> points = new ArrayList<>();

            int pointCount = 1 + random.nextInt(100);

            for (int i = 0; i < pointCount; i++) {
                GeoPoint point = new GeoPoint(
                        -80 + random.nextDouble() * 160,
                        -170 + random.nextDouble() * 340
                );

                assertTrue(tree.insert(point));
                points.add(point);
            }

            for (int q = 0; q < 10; q++) {
                GeoPoint query = new GeoPoint(
                        -90 + random.nextDouble() * 180,
                        -180 + random.nextDouble() * 360
                );

                int k = 1 + random.nextInt(pointCount);

                assertNearestMatchesBruteForce(
                        tree, points, query, k
                );
            }
        }
    }

    @Test
    void handlesPointsOnSubdivisionBoundaries() {
        QuadTree tree = new QuadTree(
                new BoundingBox(0, 0, 8, 8)
        );

        List<GeoPoint> points = List.of(
                new GeoPoint(4, 4),
                new GeoPoint(4, 2),
                new GeoPoint(4, 6),
                new GeoPoint(2, 4),
                new GeoPoint(6, 4),
                new GeoPoint(0, 0),
                new GeoPoint(8, 8),
                new GeoPoint(2, 2),
                new GeoPoint(6, 6)
        );

        points.forEach(point -> assertTrue(tree.insert(point)));

        assertEquals(points.size(), tree.size());

        List<GeoPoint> queries = List.of(
                new GeoPoint(4, 4),
                new GeoPoint(4, 3),
                new GeoPoint(3, 4),
                new GeoPoint(0, 0),
                new GeoPoint(8, 8)
        );

        for (GeoPoint query : queries) {
            assertNearestMatchesBruteForce(tree, points, query, 5);
        }
    }

    @Test
    void findsNearestPointsNearAntimeridian() {
        QuadTree tree = new QuadTree(
                new BoundingBox(-10, -180, 10, 180)
        );

        List<GeoPoint> points = List.of(
                new GeoPoint(0, -179.9),
                new GeoPoint(0, 179.8),
                new GeoPoint(1, 170),
                new GeoPoint(-1, -170),
                new GeoPoint(0, 0)
        );

        points.forEach(point -> assertTrue(tree.insert(point)));

        List<GeoPoint> queries = List.of(
                new GeoPoint(0, 179.95),
                new GeoPoint(0, -179.95),
                new GeoPoint(0, 180),
                new GeoPoint(0, -180)
        );

        for (GeoPoint query : queries) {
            assertNearestMatchesBruteForce(tree, points, query, 3);
        }
    }

    @Test
    void nearestSearchRemainsCorrectNearAntimeridianAfterSubdivision() {
        QuadTree tree = new QuadTree(
                new BoundingBox(-10, -180, 10, 180)
        );

        List<GeoPoint> points = new ArrayList<>();

        for (int i = 0; i < 30; i++) {
            GeoPoint point = new GeoPoint(
                    -9 + i * 0.6,
                    -179 + i * 12.0
            );

            if (point.latitude() <= 10 && point.longitude() <= 180) {
                assertTrue(tree.insert(point));
                points.add(point);
            }
        }

        GeoPoint query = new GeoPoint(0, 179.95);

        assertNearestMatchesBruteForce(tree, points, query, 5);
    }

    @Test
    void findsNearestPointsWhenQueryIsOutsideTreeBounds() {
        QuadTree tree = new QuadTree(
                new BoundingBox(10, 20, 20, 30)
        );

        List<GeoPoint> points = List.of(
                new GeoPoint(11, 21),
                new GeoPoint(15, 25),
                new GeoPoint(19, 29),
                new GeoPoint(12, 28),
                new GeoPoint(18, 22)
        );

        points.forEach(point -> assertTrue(tree.insert(point)));

        List<GeoPoint> queries = List.of(
                new GeoPoint(25, 25),
                new GeoPoint(5, 25),
                new GeoPoint(15, 35),
                new GeoPoint(15, 15),
                new GeoPoint(25, 35)
        );

        for (GeoPoint query : queries) {
            assertNearestMatchesBruteForce(tree, points, query, 3);
        }
    }

    @Test
    void nearestSearchMatchesBruteForceForRandomPoints() {
        QuadTree tree = new QuadTree(
                new BoundingBox(-90, -180, 90, 180)
        );

        List<GeoPoint> points = new ArrayList<>();
        Random random = new Random(42);

        for (int i = 0; i < 500; i++) {
            GeoPoint point = new GeoPoint(
                    -80 + random.nextDouble() * 160,
                    -170 + random.nextDouble() * 340
            );

            points.add(point);
            assertTrue(tree.insert(point));
        }

        GeoPoint query = new GeoPoint(16.5, 80.5);

        assertNearestMatchesBruteForce(tree, points, query, 10);
    }

    @Test
    void findsNearestPointsAcrossDifferentQuadrants() {
        QuadTree tree = new QuadTree(
                new BoundingBox(-90, -180, 90, 180)
        );

        GeoPoint query = new GeoPoint(0, 0);
        GeoPoint northWest = new GeoPoint(10, -10);
        GeoPoint northEastNear = new GeoPoint(0, 1);
        GeoPoint southEast = new GeoPoint(-10, 10);

        for (int i = 0; i < 9; i++) {
            assertTrue(tree.insert(
                    new GeoPoint(20 + i, 20 + i)
            ));
        }

        assertTrue(tree.insert(northWest));
        assertTrue(tree.insert(northEastNear));
        assertTrue(tree.insert(southEast));

        List<GeoPoint> result = tree.kNearest(query, 1);

        assertEquals(1, result.size());
        assertEquals(northEastNear, result.get(0));
    }

    @Test
    void returnsNearestPointsInDistanceOrder() {
        QuadTree tree = new QuadTree(createBoundary());

        GeoPoint query = new GeoPoint(0, 0);
        GeoPoint near = new GeoPoint(0, 1);
        GeoPoint middle = new GeoPoint(0, 2);
        GeoPoint far = new GeoPoint(0, 5);

        assertTrue(tree.insert(far));
        assertTrue(tree.insert(middle));
        assertTrue(tree.insert(near));

        List<GeoPoint> result = tree.kNearest(query, 2);

        assertEquals(2, result.size());
        assertEquals(near, result.get(0));
        assertEquals(middle, result.get(1));
    }

    @Test
    void returnsAllPointsWhenKExceedsTreeSize() {
        QuadTree tree = new QuadTree(createBoundary());

        assertTrue(tree.insert(new GeoPoint(16.5, 80.5)));
        assertTrue(tree.insert(new GeoPoint(17.0, 81.0)));

        assertEquals(
                2,
                tree.kNearest(new GeoPoint(16.5, 80.5), 10).size()
        );
    }

    @Test
    void rejectsNonPositiveK() {
        QuadTree tree = new QuadTree(createBoundary());

        assertThrows(
                IllegalArgumentException.class,
                () -> tree.kNearest(new GeoPoint(0, 0), 0)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> tree.kNearest(new GeoPoint(0, 0), -1)
        );
    }

    @Test
    void handlesManyIdenticalPointsWithoutEndlessSubdivision() {
        QuadTree tree = new QuadTree(createBoundary());
        GeoPoint point = new GeoPoint(16.5, 80.5);

        for (int i = 0; i < 100; i++) {
            assertTrue(tree.insert(point));
        }

        assertEquals(100, tree.size());
        assertEquals(100, tree.kNearest(point, 100).size());
    }

    @Test
    void insertsPointInsideBoundary() {
        QuadTree tree = new QuadTree(createBoundary());

        assertTrue(tree.insert(new GeoPoint(16.5, 80.5)));
        assertEquals(1, tree.size());
    }

    @Test
    void rejectsPointOutsideBoundary() {
        BoundingBox boundary = new BoundingBox(10, 10, 20, 20);
        QuadTree tree = new QuadTree(boundary);

        assertFalse(tree.insert(new GeoPoint(25, 15)));
        assertEquals(0, tree.size());
    }

    @Test
    void subdividesAfterCapacityIsExceeded() {
        QuadTree tree = new QuadTree(createBoundary());

        for (int i = 0; i < 9; i++) {
            assertTrue(tree.insert(new GeoPoint(i, i)));
        }

        assertEquals(9, tree.size());
    }

    @Test
    void preservesAllPointsAfterSubdivision() {
        QuadTree tree = new QuadTree(createBoundary());

        for (int i = 0; i < 20; i++) {
            assertTrue(tree.insert(new GeoPoint(i, i)));
        }

        assertEquals(20, tree.size());
    }

    @Test
    void findsNearestPointsNearNorthPole() {
        QuadTree tree = new QuadTree(
                new BoundingBox(80, -180, 90, 180)
        );

        List<GeoPoint> points = List.of(
                new GeoPoint(89.0, -120),
                new GeoPoint(89.0, 0),
                new GeoPoint(89.0, 120),
                new GeoPoint(85.0, 90),
                new GeoPoint(82.0, -45),
                new GeoPoint(88.0, 179)
        );

        points.forEach(point -> assertTrue(tree.insert(point)));

        List<GeoPoint> queries = List.of(
                new GeoPoint(89.5, 45),
                new GeoPoint(89.9, -179),
                new GeoPoint(90, 0)
        );

        for (GeoPoint query : queries) {
            assertNearestMatchesBruteForce(tree, points, query, 3);
        }
    }

    // -----------------------------------------------------------------------
    // Phase C: distance-ordered traversal correctness tests
    // -----------------------------------------------------------------------

    @Test
    void equidistantPointsReturnCorrectCountAndMatchBruteForce() {
        // Four points placed symmetrically around the origin at the same geodesic
        // distance. The ordered traversal must not drop or duplicate any of them.
        QuadTree tree = new QuadTree(createBoundary());

        List<GeoPoint> points = List.of(
                new GeoPoint(0,  1),
                new GeoPoint(0, -1),
                new GeoPoint( 1, 0),
                new GeoPoint(-1, 0)
        );
        points.forEach(p -> assertTrue(tree.insert(p)));

        GeoPoint query = new GeoPoint(0, 0);

        // Request k=4 (all points) and k=2 (subset)
        assertNearestMatchesBruteForce(tree, points, query, 4);
        assertNearestMatchesBruteForce(tree, points, query, 2);
    }

    @Test
    void equidistantPointsResultIsStableAcrossQuadrantBoundaries() {
        // Eight points evenly spread across all quadrant boundaries at identical
        // distance. Ordered traversal must produce a result that equals brute-force.
        QuadTree tree = new QuadTree(createBoundary());

        List<GeoPoint> points = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4.0;
            points.add(new GeoPoint(
                    Math.sin(angle) * 5.0,
                    Math.cos(angle) * 5.0
            ));
        }
        points.forEach(p -> assertTrue(tree.insert(p)));

        GeoPoint query = new GeoPoint(0, 0);
        assertNearestMatchesBruteForce(tree, points, query, 8);
        assertNearestMatchesBruteForce(tree, points, query, 4);
        assertNearestMatchesBruteForce(tree, points, query, 1);
    }

    @Test
    void duplicateCoordinatesAreRetainedAndRankedCorrectly() {
        // Inserting the same GeoPoint many times must not cause the ordered
        // traversal to lose duplicates or mis-rank them.
        QuadTree tree = new QuadTree(createBoundary());
        GeoPoint dup = new GeoPoint(17.3850, 78.4867);

        for (int i = 0; i < 20; i++) {
            assertTrue(tree.insert(dup));
        }

        List<GeoPoint> points = new ArrayList<>();
        for (int i = 0; i < 20; i++) points.add(dup);

        GeoPoint query = new GeoPoint(17.38, 78.48);
        assertNearestMatchesBruteForce(tree, points, query, 10);
        assertNearestMatchesBruteForce(tree, points, query, 20);
    }

    @Test
    void nearSouthPoleQueryMatchesBruteForce() {
        // The baseline identified the South Pole query as the worst-pruned case
        // under static traversal. Ordered traversal must still produce correct results.
        QuadTree tree = new QuadTree(new BoundingBox(-90, -180, 90, 180));
        List<GeoPoint> points = new ArrayList<>();
        Random rng = new Random(42);

        for (int i = 0; i < 500; i++) {
            GeoPoint p = new GeoPoint(
                    -89.9 + rng.nextDouble() * 179.8,
                    -180.0 + rng.nextDouble() * 360.0
            );
            assertTrue(tree.insert(p));
            points.add(p);
        }

        // Query from near the South Pole - the main asymmetric case
        assertNearestMatchesBruteForce(tree, points, new GeoPoint(-89.0, -45.0), 10);
        assertNearestMatchesBruteForce(tree, points, new GeoPoint(-89.9, 135.0), 10);
        assertNearestMatchesBruteForce(tree, points, new GeoPoint(-90.0, 0.0), 10);
    }

    @Test
    void nearNorthPoleQueryMatchesBruteForce() {
        QuadTree tree = new QuadTree(new BoundingBox(-90, -180, 90, 180));
        List<GeoPoint> points = new ArrayList<>();
        Random rng = new Random(99);

        for (int i = 0; i < 500; i++) {
            GeoPoint p = new GeoPoint(
                    -89.9 + rng.nextDouble() * 179.8,
                    -180.0 + rng.nextDouble() * 360.0
            );
            assertTrue(tree.insert(p));
            points.add(p);
        }

        assertNearestMatchesBruteForce(tree, points, new GeoPoint(89.0, 45.0), 10);
        assertNearestMatchesBruteForce(tree, points, new GeoPoint(89.9, -179.0), 10);
        assertNearestMatchesBruteForce(tree, points, new GeoPoint(90.0, 0.0), 10);
    }

    @Test
    void antimeridianQueriesMatchBruteForceUnderOrderedTraversal() {
        QuadTree tree = new QuadTree(new BoundingBox(-90, -180, 90, 180));
        List<GeoPoint> points = new ArrayList<>();
        Random rng = new Random(7);

        for (int i = 0; i < 300; i++) {
            GeoPoint p = new GeoPoint(
                    -10.0 + rng.nextDouble() * 20.0,
                    150.0 + rng.nextDouble() * 30.0
            );
            if (p.longitude() <= 180.0) {
                assertTrue(tree.insert(p));
                points.add(p);
            }
        }
        for (int i = 0; i < 300; i++) {
            GeoPoint p = new GeoPoint(
                    -10.0 + rng.nextDouble() * 20.0,
                    -180.0 + rng.nextDouble() * 30.0
            );
            assertTrue(tree.insert(p));
            points.add(p);
        }

        List<GeoPoint> queries = List.of(
                new GeoPoint(0, 179.95),
                new GeoPoint(0, -179.95),
                new GeoPoint(5, 180.0),
                new GeoPoint(-5, -180.0)
        );
        for (GeoPoint query : queries) {
            assertNearestMatchesBruteForce(tree, points, query, 10);
        }
    }

    @Test
    void queriesOnQuadrantMidpointBoundariesMatchBruteForce() {
        // Points placed across all four quadrant boundaries (midLat, midLon lines)
        // of a subdivided tree to exercise every path of childFor() and the
        // ordering of equal-lower-bound children.
        QuadTree tree = new QuadTree(new BoundingBox(-20, -20, 20, 20));
        List<GeoPoint> points = new ArrayList<>();

        // Seed enough points to force subdivision
        for (int i = -9; i <= 9; i++) {
            for (int j = -9; j <= 9; j++) {
                GeoPoint p = new GeoPoint(i * 2.0, j * 2.0);
                if (tree.insert(p)) points.add(p);
            }
        }

        // Query directly on midpoint boundaries
        List<GeoPoint> queries = List.of(
                new GeoPoint(0,  0),    // exact centre
                new GeoPoint(0,  5),    // on midLat, east of midLon
                new GeoPoint(5,  0),    // on midLon, north of midLat
                new GeoPoint(0, -5),    // on midLat, west
                new GeoPoint(-5, 0)     // on midLon, south
        );
        for (GeoPoint query : queries) {
            assertNearestMatchesBruteForce(tree, points, query, 5);
            assertNearestMatchesBruteForce(tree, points, query, 1);
        }
    }

    @Test
    void randomizedSweepWithMultipleDistributionsMatchesBruteForce() {
        // Broader coverage: run randomised trials under clustered and uniform data
        // to catch any remaining traversal-order regressions.
        long[] seeds = {1L, 17L, 31L, 42L, 100L};

        for (long seed : seeds) {
            Random rng = new Random(seed);
            BoundingBox bounds = new BoundingBox(-90, -180, 90, 180);
            QuadTree tree = new QuadTree(bounds);
            List<GeoPoint> points = new ArrayList<>();

            int count = 100 + rng.nextInt(400);
            for (int i = 0; i < count; i++) {
                // Mix uniform and clustered
                GeoPoint p;
                if (rng.nextBoolean()) {
                    p = new GeoPoint(-89.9 + rng.nextDouble() * 179.8,
                            -180.0 + rng.nextDouble() * 360.0);
                } else {
                    double clat = (rng.nextInt(3) - 1) * 30.0;
                    double clon = (rng.nextInt(3) - 1) * 60.0;
                    p = new GeoPoint(
                            Math.max(-89.9, Math.min(89.9, clat + rng.nextGaussian() * 5)),
                            clon + rng.nextGaussian() * 5
                    );
                }
                if (tree.insert(p)) points.add(p);
            }

            for (int q = 0; q < 15; q++) {
                GeoPoint query = new GeoPoint(
                        -90 + rng.nextDouble() * 180,
                        -180 + rng.nextDouble() * 360
                );
                int k = 1 + rng.nextInt(Math.min(20, points.size()));
                assertNearestMatchesBruteForce(tree, points, query, k);
            }
        }
    }
}
