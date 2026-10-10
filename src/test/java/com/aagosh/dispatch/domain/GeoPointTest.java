
package com.aagosh.dispatch.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeoPointTest {

    @Test
    void identicalPointsHaveZeroDistance() {
        GeoPoint point = new GeoPoint(16.5062, 80.6480);

        assertEquals(0.0, point.distanceToKm(point), 0.001);
    }

    @Test
    void calculatesDistanceBetweenDifferentPoints() {
        GeoPoint vijayawada = new GeoPoint(16.5062, 80.6480);
        GeoPoint hyderabad = new GeoPoint(17.3850, 78.4867);

        double distance = vijayawada.distanceToKm(hyderabad);

        assertTrue(distance > 240 && distance < 260,
                "Expected distance between 240 and 260 km, got " + distance);
    }

    @Test
    void rejectsInvalidLatitude() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GeoPoint(91, 80)
        );
    }

    @Test
    void rejectsInvalidLongitude() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GeoPoint(16, 181)
        );
    }
}
