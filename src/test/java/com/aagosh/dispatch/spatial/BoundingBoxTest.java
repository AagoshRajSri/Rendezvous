package com.aagosh.dispatch.spatial;

import com.aagosh.dispatch.domain.GeoPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoundingBoxTest {

    @Test
    void containsPointInsideBounds() {
        BoundingBox box = new BoundingBox(16.0, 80.0, 17.0, 81.0);

        assertTrue(box.contains(new GeoPoint(16.5, 80.5)));
    }

    @Test
    void excludesPointOutsideBounds() {
        BoundingBox box = new BoundingBox(16.0, 80.0, 17.0, 81.0);

        assertFalse(box.contains(new GeoPoint(18.0, 80.5)));
    }

    @Test
    void includesPointsOnBoundary() {
        BoundingBox box = new BoundingBox(16.0, 80.0, 17.0, 81.0);

        assertTrue(box.contains(new GeoPoint(16.0, 80.0)));
    }

    @Test
    void containsPointsOnAllFourCorners() {
        BoundingBox box = new BoundingBox(16.0, 80.0, 17.0, 81.0);

        // South-West corner (minLat, minLon)
        assertTrue(box.contains(new GeoPoint(16.0, 80.0)), "SW corner must be contained");
        // North-West corner (maxLat, minLon)
        assertTrue(box.contains(new GeoPoint(17.0, 80.0)), "NW corner must be contained");
        // South-East corner (minLat, maxLon)
        assertTrue(box.contains(new GeoPoint(16.0, 81.0)), "SE corner must be contained");
        // North-East corner (maxLat, maxLon)
        assertTrue(box.contains(new GeoPoint(17.0, 81.0)), "NE corner must be contained");
    }

    @Test
    void containsPointsOnAllFourEdges() {
        BoundingBox box = new BoundingBox(16.0, 80.0, 17.0, 81.0);

        // South edge (latitude == minLat)
        assertTrue(box.contains(new GeoPoint(16.0, 80.5)), "South edge must be contained");
        // North edge (latitude == maxLat)
        assertTrue(box.contains(new GeoPoint(17.0, 80.5)), "North edge must be contained");
        // West edge (longitude == minLon)
        assertTrue(box.contains(new GeoPoint(16.5, 80.0)), "West edge must be contained");
        // East edge (longitude == maxLon)
        assertTrue(box.contains(new GeoPoint(16.5, 81.0)), "East edge must be contained");
    }

    @Test
    void excludesPointsImmediatelyOutsideEachBoundary() {
        BoundingBox box = new BoundingBox(16.0, 80.0, 17.0, 81.0);
        double delta = 1e-7;

        // Just North of maxLat
        assertFalse(box.contains(new GeoPoint(17.0 + delta, 80.5)), "Just north of maxLat must be excluded");
        // Just South of minLat
        assertFalse(box.contains(new GeoPoint(16.0 - delta, 80.5)), "Just south of minLat must be excluded");
        // Just East of maxLon
        assertFalse(box.contains(new GeoPoint(16.5, 81.0 + delta)), "Just east of maxLon must be excluded");
        // Just West of minLon
        assertFalse(box.contains(new GeoPoint(16.5, 80.0 - delta)), "Just west of minLon must be excluded");
    }

    @Test
    void containsPointsImmediatelyInsideEachBoundary() {
        BoundingBox box = new BoundingBox(16.0, 80.0, 17.0, 81.0);
        double delta = 1e-7;

        // Just inside North boundary
        assertTrue(box.contains(new GeoPoint(17.0 - delta, 80.5)), "Just inside north must be contained");
        // Just inside South boundary
        assertTrue(box.contains(new GeoPoint(16.0 + delta, 80.5)), "Just inside south must be contained");
        // Just inside East boundary
        assertTrue(box.contains(new GeoPoint(16.5, 81.0 - delta)), "Just inside east must be contained");
        // Just inside West boundary
        assertTrue(box.contains(new GeoPoint(16.5, 80.0 + delta)), "Just inside west must be contained");
    }

    @Test
    void rejectsReversedLatitudeBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BoundingBox(17.0, 80.0, 16.0, 81.0)
        );
    }

    @Test
    void rejectsReversedLongitudeBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BoundingBox(16.0, 81.0, 17.0, 80.0)
        );
    }

    @Test
    void rejectsOutOfBoundsCoordinates() {
        // Latitude < -90
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(-90.1, 80.0, 17.0, 81.0));
        // Latitude > 90
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(16.0, 80.0, 90.1, 81.0));
        // Longitude < -180
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(16.0, -180.1, 17.0, 81.0));
        // Longitude > 180
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(16.0, 80.0, 17.0, 180.1));
    }
}
