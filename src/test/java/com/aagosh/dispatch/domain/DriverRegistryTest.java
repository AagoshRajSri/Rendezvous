package com.aagosh.dispatch.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DriverRegistry}: basic functionality, claim-state preservation,
 * timestamp-monotone ordering, concurrency, and regression against existing driver-state
 * behaviour.
 */
class DriverRegistryTest {

    private DriverRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DriverRegistry();
    }

    // =========================================================================
    // Basic functionality
    // =========================================================================

    @Test
    void registerAndRetrieveDriver() {
        Driver d = driver("D1", 17.0, 78.0);
        registry.register(d);

        Optional<Driver> found = registry.findById("D1");
        assertTrue(found.isPresent());
        assertEquals("D1", found.get().id());
        assertEquals(new GeoPoint(17.0, 78.0), found.get().location());
    }

    @Test
    void findByIdReturnsEmptyForUnknownDriver() {
        assertTrue(registry.findById("UNKNOWN").isEmpty());
    }

    @Test
    void updateLocationChangesStoredLocation() {
        Driver d = driver("D1", 17.0, 78.0);
        registry.register(d);

        boolean accepted = registry.updateLocation("D1", new GeoPoint(18.0, 79.0), 1L);
        assertTrue(accepted, "First timestamped update must be accepted");

        Driver updated = registry.findById("D1").orElseThrow();
        assertEquals(new GeoPoint(18.0, 79.0), updated.location());
    }

    @Test
    void snapshotContainsAllRegisteredDrivers() {
        registry.register(driver("D1", 17.0, 78.0));
        registry.register(driver("D2", 17.1, 78.1));
        registry.register(driver("D3", 17.2, 78.2));

        List<Driver> snap = registry.snapshot();
        Set<String> ids = new HashSet<>();
        for (Driver d : snap) ids.add(d.id());

        assertEquals(3, snap.size());
        assertTrue(ids.contains("D1"));
        assertTrue(ids.contains("D2"));
        assertTrue(ids.contains("D3"));
    }

    @Test
    void snapshotIsImmutable() {
        registry.register(driver("D1", 17.0, 78.0));
        List<Driver> snap = registry.snapshot();

        assertThrows(UnsupportedOperationException.class, () -> snap.add(driver("X", 0, 0)));
    }

    @Test
    void duplicateRegistrationIsRejected() {
        Driver original = driver("D1", 17.0, 78.0);
        registry.register(original);

        // Claim original to verify its state is preserved
        assertTrue(original.state().tryClaim("REQ-1"));

        Driver duplicate = new Driver("D1", new GeoPoint(0.0, 0.0), new DriverState());
        assertThrows(IllegalStateException.class, () -> registry.register(duplicate));

        // Existing entry must be untouched — same state, same claim
        Driver stored = registry.findById("D1").orElseThrow();
        assertSame(original.state(), stored.state(),
                "Duplicate registration must not replace existing DriverState");
        assertEquals(MatchStatus.CLAIMED, stored.state().getStatus(),
                "Claim must be preserved after rejected duplicate registration");
    }

    @Test
    void updateLocationThrowsForUnknownDriver() {
        assertThrows(IllegalStateException.class,
                () -> registry.updateLocation("GHOST", new GeoPoint(0.0, 0.0), 1L));
    }

    @Test
    void nullAndBlankInputsAreRejected() {
        // register(null) — Objects.requireNonNull throws NPE (matches domain convention)
        assertThrows(NullPointerException.class, () -> registry.register(null));

        // String inputs — requireNonBlank throws IAE for null/blank IDs
        assertThrows(IllegalArgumentException.class, () -> registry.findById(null));
        assertThrows(IllegalArgumentException.class, () -> registry.findById(""));
        assertThrows(IllegalArgumentException.class, () -> registry.findById("  "));
        assertThrows(IllegalArgumentException.class,
                () -> registry.updateLocation(null, new GeoPoint(0, 0), 1L));
        assertThrows(IllegalArgumentException.class,
                () -> registry.updateLocation("", new GeoPoint(0, 0), 1L));

        // updateLocation with null location — Objects.requireNonNull throws NPE.
        // Driver must be registered first so the NPE is reached (not IllegalStateException).
        registry.register(driver("D_NULL_LOC", 0.0, 0.0));
        assertThrows(NullPointerException.class,
                () -> registry.updateLocation("D_NULL_LOC", null, 1L));
    }

    // =========================================================================
    // Claim-state preservation
    // =========================================================================

    @Test
    void locationUpdatePreservesClaimStatus() {
        Driver d = driver("D1", 17.0, 78.0);
        registry.register(d);
        assertTrue(d.state().tryClaim("REQ-1"));

        boolean accepted = registry.updateLocation("D1", new GeoPoint(18.0, 79.0), 1L);
        assertTrue(accepted);

        Driver updated = registry.findById("D1").orElseThrow();
        assertEquals(MatchStatus.CLAIMED, updated.state().getStatus(),
                "Claim status must survive a location update");
        assertEquals("REQ-1", updated.state().getClaimedByRequestId(),
                "claimedByRequestId must be preserved after location update");
    }

    @Test
    void locationUpdateDoesNotMakeClaimedDriverAvailable() {
        Driver d = driver("D1", 17.0, 78.0);
        registry.register(d);
        assertTrue(d.state().tryClaim("REQ-1"));

        // Attempt a further claim after location update — must fail because still CLAIMED
        registry.updateLocation("D1", new GeoPoint(18.0, 79.0), 1L);

        Driver updated = registry.findById("D1").orElseThrow();
        assertFalse(updated.state().tryClaim("REQ-2"),
                "A claimed driver must not become claimable after a location update");
    }

    @Test
    void locationUpdatePreservesTheSameDriverStateObject() {
        Driver original = driver("D1", 17.0, 78.0);
        registry.register(original);
        DriverState originalState = original.state();

        registry.updateLocation("D1", new GeoPoint(18.0, 79.0), 1L);

        Driver updated = registry.findById("D1").orElseThrow();
        assertSame(originalState, updated.state(),
                "The exact same DriverState object must be reused — not recreated");
    }

    @Test
    void claimedByRequestIdPreservedAfterMultipleLocationUpdates() {
        Driver d = driver("D1", 17.0, 78.0);
        registry.register(d);
        assertTrue(d.state().tryClaim("REQ-42"));

        for (int i = 0; i < 10; i++) {
            registry.updateLocation("D1", new GeoPoint(17.0 + i * 0.01, 78.0), i + 1L);
        }

        Driver updated = registry.findById("D1").orElseThrow();
        assertEquals("REQ-42", updated.state().getClaimedByRequestId(),
                "claimedByRequestId must survive multiple sequential location updates");
    }

    // =========================================================================
    // Timestamp-monotone and unversioned ordering
    // =========================================================================

    @Test
    @DisplayName("1. An unversioned update succeeds for a driver whose current timestamp is 0L")
    void unversionedUpdateSucceedsForDriverWithZeroTimestamp() {
        Driver d = new Driver("D1", new GeoPoint(17.0, 78.0), new DriverState());
        registry.register(d);
        assertEquals(0L, registry.findById("D1").orElseThrow().updatedAt());

        boolean accepted = registry.updateLocation("D1", new GeoPoint(18.0, 79.0));
        assertTrue(accepted, "Unversioned update must succeed for a driver with updatedAt=0L");

        Driver stored = registry.findById("D1").orElseThrow();
        assertEquals(new GeoPoint(18.0, 79.0), stored.location());
        assertEquals(0L, stored.updatedAt(), "Driver must remain unversioned (updatedAt=0L)");
    }

    @Test
    @DisplayName("2. Repeated unversioned updates preserve legacy last-write-wins behavior while driver remains unversioned")
    void repeatedUnversionedUpdatesPreserveLastWriteWinsWhileUnversioned() {
        Driver d = new Driver("D1", new GeoPoint(17.0, 78.0), new DriverState());
        registry.register(d);

        for (int i = 1; i <= 5; i++) {
            boolean accepted = registry.updateLocation("D1", new GeoPoint(17.0 + i * 0.1, 78.0));
            assertTrue(accepted, "Repeated unversioned update " + i + " must succeed");
        }

        Driver stored = registry.findById("D1").orElseThrow();
        assertEquals(new GeoPoint(17.5, 78.0), stored.location(),
                "Final location must reflect the last unversioned update");
        assertEquals(0L, stored.updatedAt());
    }

    @Test
    @DisplayName("3. An unversioned update cannot overwrite a versioned location (updatedAt > 0L)")
    void unversionedUpdateCannotOverwriteVersionedLocation() {
        Driver d = new Driver("D1", new GeoPoint(17.0, 78.0), new DriverState(), 1000L);
        registry.register(d);

        // 1-arg unversioned overload
        boolean accepted1 = registry.updateLocation("D1", new GeoPoint(18.0, 79.0));
        assertFalse(accepted1, "1-arg unversioned update must not overwrite a versioned location");

        // 2-arg overload with timestamp 0L
        boolean accepted2 = registry.updateLocation("D1", new GeoPoint(19.0, 80.0), 0L);
        assertFalse(accepted2, "0L timestamp update must not overwrite a versioned location");

        Driver stored = registry.findById("D1").orElseThrow();
        assertEquals(new GeoPoint(17.0, 78.0), stored.location(),
                "Stored location must remain unchanged");
        assertEquals(1000L, stored.updatedAt());
    }

    @Test
    @DisplayName("4. A timestamped update cannot overwrite a newer timestamp")
    void timestampedUpdateCannotOverwriteNewerTimestamp() {
        Driver d = new Driver("D1", new GeoPoint(17.0, 78.0), new DriverState(), 2000L);
        registry.register(d);

        boolean accepted = registry.updateLocation("D1", new GeoPoint(18.0, 79.0), 1000L);
        assertFalse(accepted, "Stale timestamped update must be rejected");

        Driver stored = registry.findById("D1").orElseThrow();
        assertEquals(new GeoPoint(17.0, 78.0), stored.location(),
                "Registry must retain the fresher location");
        assertEquals(2000L, stored.updatedAt());
    }

    @Test
    @DisplayName("5. Equal timestamp updates remain rejected")
    void equalTimestampUpdatesRemainRejected() {
        Driver d = new Driver("D1", new GeoPoint(17.0, 78.0), new DriverState(), 2000L);
        registry.register(d);

        boolean accepted = registry.updateLocation("D1", new GeoPoint(18.0, 79.0), 2000L);
        assertFalse(accepted, "Equal timestamp update must be rejected");

        Driver stored = registry.findById("D1").orElseThrow();
        assertEquals(new GeoPoint(17.0, 78.0), stored.location());
        assertEquals(2000L, stored.updatedAt());
    }

    @Test
    @DisplayName("Strictly newer timestamp is accepted and promotes unversioned driver")
    void strictlyNewerTimestampPromotesUnversionedDriver() {
        Driver d = new Driver("D1", new GeoPoint(17.0, 78.0), new DriverState());
        registry.register(d);
        assertEquals(0L, registry.findById("D1").orElseThrow().updatedAt());

        boolean accepted = registry.updateLocation("D1", new GeoPoint(18.0, 79.0), 100L);
        assertTrue(accepted, "Timestamped update must promote unversioned driver");

        Driver stored = registry.findById("D1").orElseThrow();
        assertEquals(new GeoPoint(18.0, 79.0), stored.location());
        assertEquals(100L, stored.updatedAt());
    }

    // =========================================================================
    // Concurrency
    // =========================================================================

    /**
     * 50 threads fire concurrent location updates for the same driver using strictly
     * increasing timestamps. Only the one with the highest timestamp survives.
     */
    @RepeatedTest(5)
    void concurrentLocationUpdatesForSameDriverAreAllAtomic() throws Exception {
        Driver d = driver("D1", 0.0, 0.0);
        registry.register(d);
        assertTrue(d.state().tryClaim("REQ-CONCURRENT"));

        int threads = 50;
        List<GeoPoint> submittedLocations = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            submittedLocations.add(new GeoPoint(i * 0.01, i * 0.01));
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(threads);

        try {
            for (int i = 0; i < threads; i++) {
                final GeoPoint loc = submittedLocations.get(i);
                final long ts = i + 1L; // strictly increasing
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    registry.updateLocation("D1", loc, ts);
                    return null;
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);

        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        Driver result = registry.findById("D1").orElseThrow();

        // Final location must be one of the submitted values
        assertTrue(submittedLocations.contains(result.location()),
                "Final location must be one of the submitted values, got: " + result.location());

        // Claim state must never have been reset by a location update
        assertEquals(MatchStatus.CLAIMED, result.state().getStatus(),
                "Concurrent location updates must not reset claim state");
        assertEquals("REQ-CONCURRENT", result.state().getClaimedByRequestId());
        assertSame(d.state(), result.state(),
                "The original DriverState object must still be referenced");
    }

    /**
     * 100 threads simultaneously register 100 distinct drivers, then each thread
     * fires a location update for its own driver.
     * Verifies no registrations are lost and no driver state is corrupted.
     */
    @RepeatedTest(3)
    void concurrentRegistrationsAndUpdatesForDifferentDrivers() throws Exception {
        int n = 100;
        ExecutorService executor = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>(n);

        try {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        String id = "DRIVER-" + idx;
                        registry.register(new Driver(id, new GeoPoint(idx * 0.1 % 90, 0.0), new DriverState()));
                        registry.updateLocation(id, new GeoPoint((idx * 0.1 + 0.01) % 90, 1.0), 1L);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                    return null;
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);

        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertEquals(0, errors.get(), "No thread must throw during concurrent register+update");
        assertEquals(n, registry.size(), "All " + n + " drivers must be registered");

        // Verify every driver is retrievable and has a valid location
        List<Driver> snap = registry.snapshot();
        assertEquals(n, snap.size());
        for (Driver d : snap) {
            assertNotNull(d.location());
            assertNotNull(d.state());
        }
    }

    /**
     * Verifies claim state is preserved across a storm of concurrent location
     * updates mixed with concurrent snapshot reads.
     */
    @RepeatedTest(3)
    void snapshotIsStableDuringConcurrentRegistrationsAndUpdates() throws Exception {
        // Pre-register and claim 10 drivers (each with a real timestamp so updates work)
        List<Driver> claimed = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Driver d = new Driver("PRE-" + i, new GeoPoint(i * 0.1, 0.0), new DriverState(), 0L);
            registry.register(d);
            assertTrue(d.state().tryClaim("REQ-" + i));
            claimed.add(d);
        }

        int updaters = 20;
        int snapshotters = 5;
        int total = updaters + snapshotters;
        ExecutorService executor = Executors.newFixedThreadPool(total);
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        try {
            // Updater threads fire location updates for the pre-registered drivers
            for (int i = 0; i < updaters; i++) {
                final int idx = i % 10;
                final long ts = i + 1L;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        registry.updateLocation("PRE-" + idx, new GeoPoint(idx * 0.1 + 0.001, 1.0), ts);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                    return null;
                }));
            }

            // Snapshot threads repeatedly take snapshots and verify claim state
            for (int i = 0; i < snapshotters; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        List<Driver> snap = registry.snapshot();
                        for (Driver d : snap) {
                            if (d.id().startsWith("PRE-")) {
                                // Each PRE-* driver was claimed — must remain so
                                if (d.state().getStatus() != MatchStatus.CLAIMED) {
                                    errors.incrementAndGet();
                                }
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                    return null;
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);

        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertEquals(0, errors.get(),
                "No thread must observe a corrupted claim state or throw during concurrent operation");

        // Final verification: all pre-registered claimed drivers still claimed
        for (Driver original : claimed) {
            Driver stored = registry.findById(original.id()).orElseThrow();
            assertEquals(MatchStatus.CLAIMED, stored.state().getStatus());
            assertSame(original.state(), stored.state());
        }
    }

    // =========================================================================
    // Regression: snapshot feeds into matchers without breaking existing claims
    // =========================================================================

    @Test
    void snapshotDriversShareStateWithRegisteredDrivers() {
        Driver d = driver("D1", 17.0, 78.0);
        registry.register(d);

        // Claim via the original state object
        assertTrue(d.state().tryClaim("REQ-1"));

        // A snapshot taken after claiming must reflect the claim
        List<Driver> snap = registry.snapshot();
        assertEquals(1, snap.size());
        Driver snapshotDriver = snap.get(0);

        // The snapshot's state IS the original state — same object, same claim
        assertSame(d.state(), snapshotDriver.state(),
                "Snapshot driver must share the original DriverState");
        assertEquals(MatchStatus.CLAIMED, snapshotDriver.state().getStatus());

        // A second tryClaim via the snapshot's state must correctly fail
        assertFalse(snapshotDriver.state().tryClaim("REQ-2"),
                "tryClaim via snapshot state must fail because driver is already CLAIMED");
    }

    // =========================================================================
    // Phase 9: Metrics and Gauges
    // =========================================================================

    @Test
    @DisplayName("countByStatus accurately tallies drivers across lifecycle states")
    void countByStatusAccuratelyTalliesStates() {
        Driver d1 = driver("D1", 17.0, 78.0);
        Driver d2 = driver("D2", 17.1, 78.1);
        Driver d3 = driver("D3", 17.2, 78.2);
        Driver d4 = driver("D4", 17.3, 78.3);

        registry.register(d1);
        registry.register(d2);
        registry.register(d3);
        registry.register(d4);

        d2.state().tryClaim("REQ-2");
        d3.state().tryClaim("REQ-3");
        d3.state().transitionToEnRoute("REQ-3");
        d4.state().tryClaim("REQ-4");
        d4.state().transitionToEnRoute("REQ-4");
        d4.state().transitionToOnTrip("REQ-4");

        assertEquals(1, registry.countByStatus(MatchStatus.AVAILABLE));
        assertEquals(1, registry.countByStatus(MatchStatus.CLAIMED));
        assertEquals(1, registry.countByStatus(MatchStatus.EN_ROUTE));
        assertEquals(1, registry.countByStatus(MatchStatus.ON_TRIP));
        assertEquals(4, registry.size());
    }

    @Test
    @DisplayName("countFresh and countStale partition fleet accurately based on timestamp")
    void countFreshAndStalePartitionFleet() {
        long now = System.currentTimeMillis();
        com.aagosh.dispatch.config.DriverFreshnessConfig config =
                new com.aagosh.dispatch.config.DriverFreshnessConfig(60, 5);

        // Fresh driver: updated 5 seconds ago
        Driver d1 = new Driver("D1", new GeoPoint(17.0, 78.0), new DriverState(), now - 5_000);
        // Stale driver: updated 120 seconds ago (> 60s)
        Driver d2 = new Driver("D2", new GeoPoint(17.1, 78.1), new DriverState(), now - 120_000);
        // Unversioned driver: ts = 0L
        Driver d3 = new Driver("D3", new GeoPoint(17.2, 78.2), new DriverState(), 0L);

        registry.register(d1);
        registry.register(d2);
        registry.register(d3);

        assertEquals(1, registry.countFresh(config));
        assertEquals(2, registry.countStale(config));
    }

    @Test
    @DisplayName("DriverRegistry registers live gauges with DispatchMetrics")
    void driverRegistryRegistersLiveGauges() {
        io.micrometer.core.instrument.MeterRegistry meterRegistry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        com.aagosh.dispatch.metrics.DispatchMetrics metrics =
                new com.aagosh.dispatch.metrics.DispatchMetrics(meterRegistry);
        com.aagosh.dispatch.config.DriverFreshnessConfig config =
                new com.aagosh.dispatch.config.DriverFreshnessConfig(60, 5);

        DriverRegistry instrumentedRegistry = new DriverRegistry(metrics, config);

        long now = System.currentTimeMillis();
        Driver d1 = new Driver("D1", new GeoPoint(17.0, 78.0), new DriverState(), now - 1_000);
        Driver d2 = new Driver("D2", new GeoPoint(17.1, 78.1), new DriverState(), now - 1_000);
        instrumentedRegistry.register(d1);
        instrumentedRegistry.register(d2);

        d2.state().tryClaim("REQ-1");

        // Verify state gauges
        assertEquals(1.0, meterRegistry.find(com.aagosh.dispatch.metrics.DispatchMetrics.METRIC_DRIVERS_COUNT)
                .tag("state", "AVAILABLE").gauge().value());
        assertEquals(1.0, meterRegistry.find(com.aagosh.dispatch.metrics.DispatchMetrics.METRIC_DRIVERS_COUNT)
                .tag("state", "CLAIMED").gauge().value());
        assertEquals(2.0, meterRegistry.find(com.aagosh.dispatch.metrics.DispatchMetrics.METRIC_DRIVERS_COUNT)
                .tag("state", "TOTAL").gauge().value());

        // Verify freshness gauges
        assertEquals(2.0, meterRegistry.find(com.aagosh.dispatch.metrics.DispatchMetrics.METRIC_DRIVERS_FRESHNESS)
                .tag("status", "fresh").gauge().value());
        assertEquals(0.0, meterRegistry.find(com.aagosh.dispatch.metrics.DispatchMetrics.METRIC_DRIVERS_FRESHNESS)
                .tag("status", "stale").gauge().value());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Driver driver(String id, double lat, double lon) {
        return new Driver(id, new GeoPoint(lat, lon), new DriverState());
    }
}
