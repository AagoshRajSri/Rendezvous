package com.aagosh.dispatch.spatial;

import com.aagosh.dispatch.domain.GeoPoint;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Comparator;
import java.util.Random;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class QuadTreeTest {

    private BoundingBox createBoundary() {
        return new BoundingBox(-90, -180, 90, 180);
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
        int k = 10;

        List<GeoPoint> expected = points.stream()
                .sorted(Comparator.comparingDouble(query::distanceToKm))
                .limit(k)
                .toList();

        List<GeoPoint> actual = tree.kNearest(query, k);

        assertEquals(expected, actual);
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

        // Insert enough points to force subdivision.
        for (int i = 0; i < 9; i++) {
            assertTrue(tree.insert(new GeoPoint(20 + i, 20 + i)));
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
        BoundingBox boundary = new BoundingBox(-90, -180, 90, 180);
        QuadTree tree = new QuadTree(boundary);

        GeoPoint query = new GeoPoint(0, 0);
        GeoPoint near = new GeoPoint(0, 1);
        GeoPoint middle = new GeoPoint(0, 2);
        GeoPoint far = new GeoPoint(0, 5);

        tree.insert(far);
        tree.insert(middle);
        tree.insert(near);

        List<GeoPoint> result = tree.kNearest(query, 2);

        assertEquals(2, result.size());
        assertEquals(near, result.get(0));
        assertEquals(middle, result.get(1));
    }

    @Test
    void returnsAllPointsWhenKExceedsTreeSize() {
        QuadTree tree = new QuadTree(createBoundary());
        tree.insert(new GeoPoint(16.5, 80.5));
        tree.insert(new GeoPoint(17.0, 81.0));

        assertEquals(2, tree.kNearest(new GeoPoint(16.5, 80.5), 10).size());
    }

    @Test
    void rejectsNonPositiveK() {
        QuadTree tree = new QuadTree(createBoundary());

        assertThrows(
                IllegalArgumentException.class,
                () -> tree.kNearest(new GeoPoint(0, 0), 0)
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
}
