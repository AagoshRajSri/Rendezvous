package com.aagosh.dispatch.domain;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe, in-memory registry of active {@link Driver} instances.
 *
 * <h2>Data model</h2>
 * <p>Each registered driver is stored as an {@link AtomicReference}&lt;{@link Driver}&gt;
 * inside a {@link ConcurrentHashMap} keyed by driver ID. Because {@link Driver} is an
 * immutable record and {@link GeoPoint} is an immutable record, publishing a location
 * update means atomically replacing the stored snapshot with a new {@link Driver} that
 * carries the new location while <strong>reusing the same {@link DriverState}</strong>
 * object. The shared {@code DriverState} is the sole authority for claim status; it is
 * never recreated during a location update.
 *
 * <h2>Concurrency guarantees</h2>
 * <ul>
 *   <li><strong>Registration</strong> – guaranteed by {@link ConcurrentHashMap#putIfAbsent},
 *       which is atomic. Duplicate registration is rejected deterministically: the second
 *       call returns the existing entry without touching its state or claim.</li>
 *   <li><strong>Location updates</strong> – performed with a CAS loop on the
 *       {@link AtomicReference}. Every published location is guaranteed to be one of the
 *       values actually submitted. If two updates race, one wins and the other retries;
 *       neither is silently lost. Because the implementation has no timestamp or sequence
 *       number, it makes <em>no chronological-ordering guarantee</em>: the final stored
 *       location is whichever update was the last to succeed its CAS, which is not
 *       necessarily the one that arrived latest on the clock.</li>
 *   <li><strong>Claim preservation</strong> – the CAS loop always reads the current
 *       {@link AtomicReference} value, extracts its {@link DriverState}, and builds the
 *       replacement snapshot from that same state object. A location update can therefore
 *       never reset a {@code CLAIMED}, {@code EN_ROUTE}, or {@code ON_TRIP} status.</li>
 *   <li><strong>Snapshots</strong> – {@link #snapshot()} iterates the
 *       {@link AtomicReference} values and returns an immutable {@link List}. The list is
 *       a stable collection at the instant of iteration; it is <em>not</em> an atomic
 *       cross-driver consistent view, because drivers registered or updated concurrently
 *       during the iteration may or may not appear in the result.</li>
 *   <li><strong>No registry lock during matching</strong> – callers retrieve a snapshot and
 *       pass it to the matcher. The matcher holds no reference to the registry, so spatial
 *       indexing and auction computation proceed without any registry lock.</li>
 * </ul>
 */
@Component
public final class DriverRegistry {

    /**
     * Map from driver ID → mutable holder for the current driver snapshot.
     * The holder is an {@link AtomicReference} to support lock-free CAS updates.
     */
    private final ConcurrentHashMap<String, AtomicReference<Driver>> registry =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Registers a driver with the registry.
     *
     * <p><strong>Duplicate-registration policy:</strong> if a driver with the same ID is
     * already registered, the existing entry (including its claim state) is preserved
     * unchanged and this method throws {@link IllegalStateException}. Silent replacement is
     * not allowed because it could overwrite live claim state.
     *
     * @param driver the driver to register; must not be {@code null}
     * @throws IllegalArgumentException if {@code driver} is {@code null}
     * @throws IllegalStateException    if a driver with the same ID is already registered
     */
    public void register(Driver driver) {
        Objects.requireNonNull(driver, "driver");

        AtomicReference<Driver> existing =
                registry.putIfAbsent(driver.id(), new AtomicReference<>(driver));

        if (existing != null) {
            throw new IllegalStateException(
                    "Driver already registered: " + driver.id());
        }
    }

    // -------------------------------------------------------------------------
    // Retrieval
    // -------------------------------------------------------------------------

    /**
     * Returns the current snapshot of the registered driver with the given ID.
     *
     * @param driverId the driver ID; must not be {@code null} or blank
     * @return an {@link Optional} containing the current driver snapshot, or
     *         {@link Optional#empty()} if no driver with that ID is registered
     * @throws IllegalArgumentException if {@code driverId} is {@code null} or blank
     */
    public Optional<Driver> findById(String driverId) {
        requireNonBlank(driverId, "driverId");

        AtomicReference<Driver> ref = registry.get(driverId);
        if (ref == null) {
            return Optional.empty();
        }
        return Optional.of(ref.get());
    }

    // -------------------------------------------------------------------------
    // Location update
    // -------------------------------------------------------------------------

    /**
     * Atomically updates the location of a registered driver.
     *
     * <p>The update is performed with a compare-and-set loop on the driver's
     * {@link AtomicReference}. The replacement {@link Driver} snapshot is built by
     * constructing a new record with the new location and the <em>same</em>
     * {@link DriverState} object as the current snapshot. This guarantees that:
     * <ul>
     *   <li>The claim status ({@code AVAILABLE}, {@code CLAIMED}, …) is never changed.</li>
     *   <li>{@code claimedByRequestId} is never cleared.</li>
     *   <li>The authoritative {@link DriverState} reference is preserved.</li>
     * </ul>
     *
     * <p><strong>No chronological ordering guarantee.</strong> If two location updates race
     * for the same driver, both will eventually succeed their respective CAS (one may retry),
     * and the final stored location will be whichever committed last. No timestamp or
     * sequence number is used to resolve ordering.
     *
     * @param driverId    the ID of the driver to update; must not be {@code null} or blank
     * @param newLocation the new location; must not be {@code null}
     * @throws IllegalArgumentException if {@code driverId} is blank or {@code newLocation}
     *                                  is {@code null}
     * @throws IllegalStateException    if no driver with {@code driverId} is registered
     */
    public void updateLocation(String driverId, GeoPoint newLocation) {
        requireNonBlank(driverId, "driverId");
        Objects.requireNonNull(newLocation, "newLocation");

        AtomicReference<Driver> ref = registry.get(driverId);
        if (ref == null) {
            throw new IllegalStateException(
                    "No driver registered with ID: " + driverId);
        }

        // CAS loop: read current snapshot, build replacement preserving state, swap.
        Driver current;
        Driver updated;
        do {
            current = ref.get();
            // Reuse the exact same DriverState object — never recreate it.
            updated = new Driver(current.id(), newLocation, current.state());
        } while (!ref.compareAndSet(current, updated));
    }

    // -------------------------------------------------------------------------
    // Snapshot
    // -------------------------------------------------------------------------

    /**
     * Returns an immutable snapshot of all currently registered driver snapshots.
     *
     * <p>The returned list is stable: it is not backed by the internal map. However, it is
     * <em>not</em> an atomic, cross-driver consistent view. Drivers registered or updated
     * concurrently during this call may or may not appear with their most recent location.
     * Each individual driver snapshot is atomically read from its {@link AtomicReference},
     * so per-driver data is self-consistent.
     *
     * @return an immutable {@link List} of all current driver snapshots; never {@code null}
     */
    public List<Driver> snapshot() {
        Collection<AtomicReference<Driver>> refs = registry.values();
        List<Driver> result = new java.util.ArrayList<>(refs.size());
        for (AtomicReference<Driver> ref : refs) {
            result.add(ref.get());
        }
        return List.copyOf(result);
    }

    // -------------------------------------------------------------------------
    // Package-private helpers (for testing)
    // -------------------------------------------------------------------------

    /** Returns the number of registered drivers. */
    int size() {
        return registry.size();
    }

    // -------------------------------------------------------------------------
    // Internal validation
    // -------------------------------------------------------------------------

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }
}
