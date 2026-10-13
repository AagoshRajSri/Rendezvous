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

    // -------------------------------------------------------------------------
    // Message consistency — GeoPoint errors must match the API contract
    // -------------------------------------------------------------------------

    @Test
    void latitudeValidationMessageMatchesApiContract() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new GeoPoint(91.0, 0.0)
        );
        assertEquals("latitude must be a finite number between -90.0 and 90.0", ex.getMessage());
    }

    @Test
    void longitudeValidationMessageMatchesApiContract() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new GeoPoint(0.0, 181.0)
        );
        assertEquals("longitude must be a finite number between -180.0 and 180.0", ex.getMessage());
    }

    @Test
    void nonFiniteLatitudeRejectedWithCorrectMessage() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new GeoPoint(Double.NaN, 0.0)
        );
        assertEquals("latitude must be a finite number between -90.0 and 90.0", ex.getMessage());
    }

    @Test
    void nonFiniteLongitudeRejectedWithCorrectMessage() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new GeoPoint(0.0, Double.POSITIVE_INFINITY)
        );
        assertEquals("longitude must be a finite number between -180.0 and 180.0", ex.getMessage());
    }
}
