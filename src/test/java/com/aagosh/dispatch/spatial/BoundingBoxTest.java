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
    void rejectsReversedLatitudeBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BoundingBox(17.0, 80.0, 16.0, 81.0)
        );
    }
}
