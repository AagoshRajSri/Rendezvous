package com.aagosh.dispatch.domain;

import com.aagosh.dispatch.config.DriverFreshnessConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests verifying freshness filtering in {@link DriverRegistry#snapshotFresh(DriverFreshnessConfig)}
 * and {@link DriverRegistry#isFresh(String, DriverFreshnessConfig)}.
 */
class DriverRegistryFreshnessTest {

    private DriverRegistry registry;
    // Config: 60s staleness, 5s future skew
    private final DriverFreshnessConfig config = new DriverFreshnessConfig(60, 5);

    @BeforeEach
    void setUp() {
        registry = new DriverRegistry();
    }

    @Test
    @DisplayName("snapshotFresh excludes drivers with updatedAt <= 0")
    void snapshotFreshExcludesZeroTimestampDrivers() {
        Driver dZero = new Driver("D-ZERO", new GeoPoint(12.9, 77.6), new DriverState(), 0L);
        Driver dNeg  = new Driver("D-NEG",  new GeoPoint(12.9, 77.6), new DriverState(), -1L);
        registry.register(dZero);
        registry.register(dNeg);

        List<Driver> fresh = registry.snapshotFresh(config);
        assertTrue(fresh.isEmpty(), "Drivers with updatedAt <= 0 must be excluded");
        assertEquals(2, registry.snapshot().size(), "Full snapshot must still retain them");
    }

    @Test
    @DisplayName("snapshotFresh excludes drivers older than staleness threshold")
    void snapshotFreshExcludesStaleDrivers() {
        long now = System.currentTimeMillis();
        long staleTs = now - (61 * 1000L); // 61s ago, threshold is 60s
        Driver dStale = new Driver("D-STALE", new GeoPoint(12.9, 77.6), new DriverState(), staleTs);
        registry.register(dStale);

        List<Driver> fresh = registry.snapshotFresh(config);
        assertTrue(fresh.isEmpty(), "Driver older than 60s staleness threshold must be excluded");
    }

    @Test
    @DisplayName("snapshotFresh excludes drivers with future timestamps beyond maxFutureSkew")
    void snapshotFreshExcludesFutureSkewedDrivers() {
        long now = System.currentTimeMillis();
        long futureTs = now + (10 * 1000L); // 10s in future, max skew is 5s
        Driver dFuture = new Driver("D-FUTURE", new GeoPoint(12.9, 77.6), new DriverState(), futureTs);
        registry.register(dFuture);

        List<Driver> fresh = registry.snapshotFresh(config);
        assertTrue(fresh.isEmpty(), "Driver with timestamp > 5s in future must be excluded");
    }

    @Test
    @DisplayName("snapshotFresh includes drivers within the freshness window")
    void snapshotFreshIncludesValidDrivers() {
        long now = System.currentTimeMillis();
        Driver dRecent = new Driver("D-RECENT", new GeoPoint(12.9, 77.6), new DriverState(), now - 10_000L); // 10s ago
        Driver dNow    = new Driver("D-NOW",    new GeoPoint(12.9, 77.6), new DriverState(), now);
        Driver dSkewOk = new Driver("D-SKEW",   new GeoPoint(12.9, 77.6), new DriverState(), now + 2_000L);  // 2s future skew <= 5s
        registry.register(dRecent);
        registry.register(dNow);
        registry.register(dSkewOk);

        List<Driver> fresh = registry.snapshotFresh(config);
        assertEquals(3, fresh.size(), "Drivers within valid window must be included");
    }

    @Test
    @DisplayName("snapshotFresh filters mixed population correctly")
    void snapshotFreshMixedPopulation() {
        long now = System.currentTimeMillis();
        registry.register(new Driver("D-VALID",  new GeoPoint(12.9, 77.6), new DriverState(), now - 5_000L));
        registry.register(new Driver("D-ZERO",   new GeoPoint(12.9, 77.6), new DriverState(), 0L));
        registry.register(new Driver("D-STALE",  new GeoPoint(12.9, 77.6), new DriverState(), now - 120_000L));
        registry.register(new Driver("D-FUTURE", new GeoPoint(12.9, 77.6), new DriverState(), now + 30_000L));

        List<Driver> fresh = registry.snapshotFresh(config);
        assertEquals(1, fresh.size());
        assertEquals("D-VALID", fresh.get(0).id());
    }

    @Test
    @DisplayName("snapshotFresh throws NullPointerException on null config")
    void snapshotFreshNullConfig() {
        assertThrows(NullPointerException.class, () -> registry.snapshotFresh(null));
    }

    @Test
    @DisplayName("isFresh returns false for unknown driverId")
    void isFreshUnknownDriver() {
        assertFalse(registry.isFresh("UNKNOWN", config));
    }

    @Test
    @DisplayName("isFresh returns false for driver with updatedAt <= 0")
    void isFreshZeroTimestamp() {
        registry.register(new Driver("D1", new GeoPoint(12.9, 77.6), new DriverState(), 0L));
        assertFalse(registry.isFresh("D1", config));
    }

    @Test
    @DisplayName("isFresh returns false for stale driver")
    void isFreshStaleDriver() {
        long now = System.currentTimeMillis();
        registry.register(new Driver("D1", new GeoPoint(12.9, 77.6), new DriverState(), now - 70_000L));
        assertFalse(registry.isFresh("D1", config));
    }

    @Test
    @DisplayName("isFresh returns false for driver with excessive future skew")
    void isFreshFutureSkewDriver() {
        long now = System.currentTimeMillis();
        registry.register(new Driver("D1", new GeoPoint(12.9, 77.6), new DriverState(), now + 10_000L));
        assertFalse(registry.isFresh("D1", config));
    }

    @Test
    @DisplayName("isFresh returns true for driver within freshness window")
    void isFreshValidDriver() {
        long now = System.currentTimeMillis();
        registry.register(new Driver("D1", new GeoPoint(12.9, 77.6), new DriverState(), now - 15_000L));
        assertTrue(registry.isFresh("D1", config));
    }

    @Test
    @DisplayName("isFresh validates inputs")
    void isFreshInputValidation() {
        assertThrows(IllegalArgumentException.class, () -> registry.isFresh(null, config));
        assertThrows(IllegalArgumentException.class, () -> registry.isFresh("", config));
        assertThrows(IllegalArgumentException.class, () -> registry.isFresh("   ", config));
        assertThrows(NullPointerException.class, () -> registry.isFresh("D1", null));
    }
}
