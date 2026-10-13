package com.aagosh.dispatch.domain;

import org.junit.jupiter.api.BeforeEach;
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
 * concurrency, and regression against existing driver-state behaviour.
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

        registry.updateLocation("D1", new GeoPoint(18.0, 79.0));

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
                () -> registry.updateLocation("GHOST", new GeoPoint(0.0, 0.0)));
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
                () -> registry.updateLocation(null, new GeoPoint(0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.updateLocation("", new GeoPoint(0, 0)));

        // updateLocation with null location — Objects.requireNonNull throws NPE.
        // Driver must be registered first so the NPE is reached (not IllegalStateException).
        registry.register(driver("D_NULL_LOC", 0.0, 0.0));
        assertThrows(NullPointerException.class,
                () -> registry.updateLocation("D_NULL_LOC", null));
    }

    // =========================================================================
    // Claim-state preservation
    // =========================================================================

    @Test
    void locationUpdatePreservesClaimStatus() {
        Driver d = driver("D1", 17.0, 78.0);
        registry.register(d);
        assertTrue(d.state().tryClaim("REQ-1"));

        registry.updateLocation("D1", new GeoPoint(18.0, 79.0));

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
        registry.updateLocation("D1", new GeoPoint(18.0, 79.0));

        Driver updated = registry.findById("D1").orElseThrow();
        assertFalse(updated.state().tryClaim("REQ-2"),
                "A claimed driver must not become claimable after a location update");
    }

    @Test
    void locationUpdatePreservesTheSameDriverStateObject() {
        Driver original = driver("D1", 17.0, 78.0);
        registry.register(original);
        DriverState originalState = original.state();

        registry.updateLocation("D1", new GeoPoint(18.0, 79.0));

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
            registry.updateLocation("D1", new GeoPoint(17.0 + i * 0.01, 78.0));
        }

        Driver updated = registry.findById("D1").orElseThrow();
        assertEquals("REQ-42", updated.state().getClaimedByRequestId(),
                "claimedByRequestId must survive multiple sequential location updates");
    }

    // =========================================================================
    // Concurrency
    // =========================================================================

    /**
     * 50 threads fire concurrent location updates for the same driver.
     * Each update uses a valid, distinct GeoPoint.
     *
     * Guarantees verified:
     * - No exception propagates from the registry.
     * - The final stored location is one of the submitted values.
     * - Claim state is never reset.
     * - No chronological-ordering guarantee is asserted (implementation has none).
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
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    registry.updateLocation("D1", loc);
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
                        registry.updateLocation(id, new GeoPoint((idx * 0.1 + 0.01) % 90, 1.0));
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
        // Pre-register and claim 10 drivers
        List<Driver> claimed = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Driver d = driver("PRE-" + i, i * 0.1, 0.0);
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
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        registry.updateLocation("PRE-" + idx, new GeoPoint(idx * 0.1 + 0.001, 1.0));
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
    // Helpers
    // =========================================================================

    private static Driver driver(String id, double lat, double lon) {
        return new Driver(id, new GeoPoint(lat, lon), new DriverState());
    }
}
