package com.aagosh.dispatch.domain;

import com.aagosh.dispatch.config.DriverFreshnessConfig;
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
 * <h2>Timestamp-monotone and unversioned ordering</h2>
 * <p>{@link #updateLocation(String, GeoPoint, long)} enforces strict monotone ordering for
 * positive timestamps: an incoming update is accepted only if {@code newTimestamp} is strictly
 * greater than the {@code updatedAt} stored in the current snapshot. If the driver is currently
 * unversioned ({@code updatedAt == 0L}), any positive timestamp promotes the driver to versioned.
 * If the driver already has a positive timestamp, equal or older timestamps are rejected.
 *
 * <p>Unversioned updates via {@link #updateLocation(String, GeoPoint)} (or non-positive timestamps):
 * <ul>
 *   <li>If the driver is unversioned ({@code updatedAt == 0L}), unversioned updates succeed
 *       and preserve legacy last-write-wins behavior.</li>
 *   <li>If the driver has already been updated with a known positive timestamp ({@code updatedAt > 0L}),
 *       unversioned updates cannot overwrite it and are rejected.</li>
 * </ul>
 *
 * <h2>Concurrency guarantees</h2>
 * <ul>
 *   <li><strong>Registration</strong> - guaranteed by {@link ConcurrentHashMap#putIfAbsent},
 *       which is atomic. Duplicate registration is rejected deterministically: the second
 *       call returns the existing entry without touching its state or claim.</li>
 *   <li><strong>Location updates</strong> - performed with a CAS loop on the
 *       {@link AtomicReference}. Every accepted location is guaranteed to be one of the
 *       values actually submitted. If two updates race, only the one with the strictly
 *       larger timestamp will eventually win; stale updates are silently rejected.</li>
 *   <li><strong>Claim preservation</strong> - the CAS loop always reads the current
 *       {@link AtomicReference} value, extracts its {@link DriverState}, and builds the
 *       replacement snapshot from that same state object. A location update can therefore
 *       never reset a {@code CLAIMED}, {@code EN_ROUTE}, or {@code ON_TRIP} status.</li>
 *   <li><strong>Snapshots</strong> - {@link #snapshot()} iterates the
 *       {@link AtomicReference} values and returns an immutable {@link List}. The list is
 *       a stable collection at the instant of iteration; it is <em>not</em> an atomic
 *       cross-driver consistent view, because drivers registered or updated concurrently
 *       during the iteration may or may not appear in the result.</li>
 *   <li><strong>No registry lock during matching</strong> - callers retrieve a snapshot and
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

    private final com.aagosh.dispatch.metrics.DispatchMetrics dispatchMetrics;
    private final DriverFreshnessConfig freshnessConfig;

    public DriverRegistry() {
        this(null, DriverFreshnessConfig.DEFAULT);
    }

    public DriverRegistry(DriverFreshnessConfig freshnessConfig) {
        this(null, freshnessConfig);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DriverRegistry(@org.springframework.beans.factory.annotation.Autowired(required = false) com.aagosh.dispatch.metrics.DispatchMetrics dispatchMetrics,
                          @org.springframework.beans.factory.annotation.Autowired(required = false) DriverFreshnessConfig freshnessConfig) {
        this.dispatchMetrics = dispatchMetrics;
        this.freshnessConfig = freshnessConfig != null ? freshnessConfig : DriverFreshnessConfig.DEFAULT;
        if (dispatchMetrics != null) {
            registerGauges(dispatchMetrics, this.freshnessConfig);
        }
    }

    private void registerGauges(com.aagosh.dispatch.metrics.DispatchMetrics metrics, DriverFreshnessConfig config) {
        metrics.registerDriverStateGauge("AVAILABLE", () -> countByStatus(MatchStatus.AVAILABLE));
        metrics.registerDriverStateGauge("CLAIMED", () -> countByStatus(MatchStatus.CLAIMED));
        metrics.registerDriverStateGauge("EN_ROUTE", () -> countByStatus(MatchStatus.EN_ROUTE));
        metrics.registerDriverStateGauge("ON_TRIP", () -> countByStatus(MatchStatus.ON_TRIP));
        metrics.registerDriverStateGauge("TOTAL", this::size);

        metrics.registerDriverFreshnessGauge("fresh", () -> countFresh(config));
        metrics.registerDriverFreshnessGauge("stale", () -> countStale(config));
    }

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
    // Location update (timestamp-aware)
    // -------------------------------------------------------------------------

    /**
     * Atomically updates the location of a registered driver using a timestamped fix.
     *
     * <p><strong>Timestamped ordering:</strong> If {@code newTimestamp > 0}, the update is
     * accepted only if {@code newTimestamp} is strictly greater than the stored {@code updatedAt}.
     * If the driver is currently unversioned ({@code updatedAt == 0L}), any positive timestamp
     * promotes the driver to versioned. If the driver already has a positive timestamp and
     * {@code current.updatedAt() >= newTimestamp}, the update is rejected as stale or duplicate.
     *
     * <p><strong>Unversioned fallback:</strong> If {@code newTimestamp <= 0}, this method delegates
     * to {@link #updateLocation(String, GeoPoint)}, treating the update as unversioned.
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
     * @param driverId     the ID of the driver to update; must not be {@code null} or blank
     * @param newLocation  the new location; must not be {@code null}
     * @param newTimestamp epoch-millisecond timestamp for this location fix; &le; 0 for unversioned
     * @return {@code true} if the location was accepted and stored;
     *         {@code false} if the update was rejected (stale/duplicate timestamp or unversioned write to versioned driver)
     * @throws IllegalArgumentException if {@code driverId} is blank or {@code newLocation} is {@code null}
     * @throws IllegalStateException    if no driver with {@code driverId} is registered
     */
    public boolean updateLocation(String driverId, GeoPoint newLocation, long newTimestamp) {
        if (newTimestamp <= 0L) {
            return updateLocation(driverId, newLocation);
        }

        requireNonBlank(driverId, "driverId");
        Objects.requireNonNull(newLocation, "newLocation");

        AtomicReference<Driver> ref = registry.get(driverId);
        if (ref == null) {
            throw new IllegalStateException(
                    "No driver registered with ID: " + driverId);
        }

        // CAS loop: read current snapshot; reject if stale or duplicate; otherwise swap.
        Driver current;
        Driver updated;
        do {
            current = ref.get();
            // Reject if the driver already has an equal or newer timestamp.
            // If current.updatedAt() == 0L, 0L >= newTimestamp is false, so it is accepted.
            if (current.updatedAt() >= newTimestamp) {
                return false;
            }
            // Reuse the exact same DriverState object - never recreate it.
            updated = new Driver(current.id(), newLocation, current.state(), newTimestamp);
        } while (!ref.compareAndSet(current, updated));

        return true;
    }

    /**
     * Unversioned location update, preserved for legacy callers and callers that have
     * no ordering information.
     *
     * <p><strong>Semantics:</strong>
     * <ul>
     *   <li>If the driver is unversioned ({@code current.updatedAt() == 0L}), the update succeeds
     *       and the driver remains unversioned ({@code updatedAt == 0L}). Repeated unversioned updates
     *       preserve the legacy last-write-wins behavior.</li>
     *   <li>If the driver has already been updated with a known positive timestamp
     *       ({@code current.updatedAt() > 0L}), an unversioned update cannot prove freshness and is
     *       rejected (returns {@code false}) without modifying the stored location.</li>
     * </ul>
     *
     * @param driverId    the ID of the driver to update; must not be {@code null} or blank
     * @param newLocation the new location; must not be {@code null}
     * @return {@code true} if the update was accepted; {@code false} if rejected because the driver is versioned
     * @throws IllegalArgumentException if {@code driverId} is blank or {@code newLocation} is {@code null}
     * @throws IllegalStateException    if no driver with {@code driverId} is registered
     */
    public boolean updateLocation(String driverId, GeoPoint newLocation) {
        requireNonBlank(driverId, "driverId");
        Objects.requireNonNull(newLocation, "newLocation");

        AtomicReference<Driver> ref = registry.get(driverId);
        if (ref == null) {
            throw new IllegalStateException(
                    "No driver registered with ID: " + driverId);
        }

        Driver current;
        Driver updated;
        do {
            current = ref.get();
            // An unversioned update cannot overwrite a versioned location (updatedAt > 0L).
            if (current.updatedAt() > 0L) {
                return false;
            }
            // While unversioned (updatedAt == 0L), accept update preserving legacy last-write-wins.
            updated = new Driver(current.id(), newLocation, current.state(), 0L);
        } while (!ref.compareAndSet(current, updated));

        return true;
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

    /**
     * Returns an immutable snapshot of drivers whose last-known location is verified and fresh.
     *
     * <p>A driver is included only when <em>all</em> of the following hold:
     * <ol>
     *   <li>{@code updatedAt > 0L} - a verified, timestamped location fix has been received
     *       (timestamp-less drivers that arrived only via legacy unversioned pings are excluded).</li>
     *   <li>{@code updatedAt >= nowMs - stalenessMs} - the location is not older than the
     *       configured staleness threshold.</li>
     *   <li>{@code updatedAt <= nowMs + maxFutureSkewMs} - the timestamp is not implausibly
     *       far in the future (guards against misbehaving or spoofed clients).</li>
     * </ol>
     *
     * <p>This method should be used by the matching pipeline in place of {@link #snapshot()}
     * to prevent stale or untrusted drivers from being selected for new rides.
     *
     * @param config the freshness thresholds to apply; must not be {@code null}
     * @return an immutable {@link List} of fresh driver snapshots; never {@code null}
     * @throws NullPointerException if {@code config} is {@code null}
     */
    public List<Driver> snapshotFresh(DriverFreshnessConfig config) {
        Objects.requireNonNull(config, "config");

        long nowMs         = System.currentTimeMillis();
        long stalenessMs   = config.stalenessSeconds()   * 1_000L;
        long maxFutureSkewMs = config.maxFutureSkewSeconds() * 1_000L;

        Collection<AtomicReference<Driver>> refs = registry.values();
        List<Driver> result = new java.util.ArrayList<>(refs.size());
        for (AtomicReference<Driver> ref : refs) {
            Driver d = ref.get();
            long ts = d.updatedAt();
            if (ts <= 0L) {
                // No verified timestamp - exclude from production matching.
                continue;
            }
            if (ts < nowMs - stalenessMs) {
                // Location is older than the staleness threshold.
                continue;
            }
            if (ts > nowMs + maxFutureSkewMs) {
                // Timestamp is implausibly far in the future.
                continue;
            }
            result.add(d);
        }
        return List.copyOf(result);
    }

    /**
     * Returns {@code true} if the driver identified by {@code driverId} has a fresh,
     * verified location according to the supplied {@link DriverFreshnessConfig}.
     *
     * <p>Equivalent to checking whether a driver with this ID appears in
     * {@link #snapshotFresh(DriverFreshnessConfig)}, but without building the full list.
     *
     * @param driverId the driver ID; must not be {@code null} or blank
     * @param config   the freshness thresholds to apply; must not be {@code null}
     * @return {@code true} if the driver exists and has a fresh, verified location
     * @throws IllegalArgumentException if {@code driverId} is {@code null} or blank
     * @throws NullPointerException     if {@code config} is {@code null}
     */
    public boolean isFresh(String driverId, DriverFreshnessConfig config) {
        requireNonBlank(driverId, "driverId");
        Objects.requireNonNull(config, "config");

        AtomicReference<Driver> ref = registry.get(driverId);
        if (ref == null) {
            return false;
        }
        Driver d = ref.get();
        long ts  = d.updatedAt();
        if (ts <= 0L) {
            return false;
        }
        long nowMs           = System.currentTimeMillis();
        long stalenessMs     = config.stalenessSeconds()      * 1_000L;
        long maxFutureSkewMs = config.maxFutureSkewSeconds()  * 1_000L;
        return ts >= nowMs - stalenessMs && ts <= nowMs + maxFutureSkewMs;
    }

    // -------------------------------------------------------------------------
    // Package-private helpers (for testing)
    // -------------------------------------------------------------------------

    /** Returns the number of registered drivers. */
    public int size() {
        return registry.size();
    }

    /**
     * Returns the count of registered drivers currently in the specified lifecycle status.
     * Thread-safe query suitable for Prometheus gauge sampling.
     *
     * @param status the target lifecycle match status; must not be null
     * @return the number of drivers currently in the status
     */
    public int countByStatus(MatchStatus status) {
        Objects.requireNonNull(status, "status");
        int count = 0;
        for (AtomicReference<Driver> ref : registry.values()) {
            Driver d = ref.get();
            if (d != null && d.state().getStatus() == status) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the count of registered drivers with fresh, verified location updates.
     * Thread-safe query suitable for Prometheus gauge sampling.
     *
     * @param config the freshness configuration thresholds
     * @return count of drivers satisfying the freshness criteria
     */
    public int countFresh(DriverFreshnessConfig config) {
        if (config == null) return 0;
        long nowMs = System.currentTimeMillis();
        long stalenessMs = config.stalenessSeconds() * 1000L;
        long maxFutureSkewMs = config.maxFutureSkewSeconds() * 1000L;
        int count = 0;
        for (AtomicReference<Driver> ref : registry.values()) {
            Driver d = ref.get();
            if (d != null) {
                long ts = d.updatedAt();
                if (ts > 0L && ts >= nowMs - stalenessMs && ts <= nowMs + maxFutureSkewMs) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Returns the count of registered drivers whose location updates are stale or unverified.
     * Thread-safe query suitable for Prometheus gauge sampling.
     *
     * @param config the freshness configuration thresholds
     * @return count of drivers with stale or missing timestamps
     */
    public int countStale(DriverFreshnessConfig config) {
        if (config == null) return 0;
        long nowMs = System.currentTimeMillis();
        long stalenessMs = config.stalenessSeconds() * 1000L;
        long maxFutureSkewMs = config.maxFutureSkewSeconds() * 1000L;
        int count = 0;
        for (AtomicReference<Driver> ref : registry.values()) {
            Driver d = ref.get();
            if (d != null) {
                long ts = d.updatedAt();
                if (ts <= 0L || ts < nowMs - stalenessMs || ts > nowMs + maxFutureSkewMs) {
                    count++;
                }
            }
        }
        return count;
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
