
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
                .sorted(Comparator.comparingDouble(query::distanceToKm))
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
}
