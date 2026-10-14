package com.aagosh.dispatch.domain;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe, lock-free state machine for driver claim and ride execution lifecycle.
 *
 * <h2>Supported Lifecycle Transitions</h2>
 * <ul>
 *   <li>{@code AVAILABLE -> CLAIMED} via {@link #tryClaim(String)}</li>
 *   <li>{@code CLAIMED -> EN_ROUTE} via {@link #transitionToEnRoute(String)}</li>
 *   <li>{@code EN_ROUTE -> ON_TRIP} via {@link #transitionToOnTrip(String)}</li>
 *   <li>{@code ON_TRIP -> AVAILABLE} via {@link #completeTrip(String)}</li>
 *   <li>{@code CLAIMED | EN_ROUTE -> AVAILABLE} via {@link #cancelTrip(String)}</li>
 * </ul>
 *
 * <h2>Safety and Idempotency Guarantees</h2>
 * <ul>
 *   <li><strong>Strict ownership checks:</strong> Every active transition requires that the
 *       caller's {@code requestId} matches the current {@code claimedByRequestId}.</li>
 *   <li><strong>Post-pickup cancellation restriction:</strong> Cancellation is permitted
 *       only while {@code CLAIMED} or {@code EN_ROUTE}. Once the driver has picked up
 *       the passenger ({@code ON_TRIP}), cancellation is strictly disallowed.</li>
 *   <li><strong>Idempotency & Re-claim safety:</strong> Repeating an already completed
 *       transition returns {@code true}. When released to {@code AVAILABLE}, the releasing
 *       request is remembered. A delayed or repeated cancellation for an earlier request
 *       cannot release a driver who has since been claimed by a different request.</li>
 * </ul>
 */
public final class DriverState {

    private record State(
            MatchStatus status,
            String claimedByRequestId,
            String lastReleasedByRequestId
    ) {}

    private final AtomicReference<State> state =
            new AtomicReference<>(
                    new State(MatchStatus.AVAILABLE, null, null));

    /**
     * Attempts to atomically claim an AVAILABLE driver for the given ride request.
     *
     * @param requestId the request ID claiming this driver; must not be blank
     * @return {@code true} if the driver was successfully claimed; {@code false} if already claimed
     */
    public boolean tryClaim(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }

        State current = state.get();
        if (current.status() != MatchStatus.AVAILABLE) {
            return false;
        }

        State claimed = new State(MatchStatus.CLAIMED, requestId, null);
        return state.compareAndSet(current, claimed);
    }

    /**
     * Advances driver status from {@link MatchStatus#CLAIMED} to {@link MatchStatus#EN_ROUTE}.
     *
     * <p>Ownership check: only the request that currently holds the claim can advance the driver.
     * <p>Idempotency: if already {@code EN_ROUTE} for the same {@code requestId}, returns {@code true}.
     *
     * @param requestId the request ID holding the claim; must not be blank
     * @return {@code true} if transitioned or already in desired state; {@code false} otherwise
     */
    public boolean transitionToEnRoute(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }

        while (true) {
            State current = state.get();

            // Idempotent: already EN_ROUTE for this exact request
            if (current.status() == MatchStatus.EN_ROUTE
                    && requestId.equals(current.claimedByRequestId())) {
                return true;
            }

            // Must currently be CLAIMED by this exact request
            if (current.status() != MatchStatus.CLAIMED
                    || !requestId.equals(current.claimedByRequestId())) {
                return false;
            }

            State next = new State(MatchStatus.EN_ROUTE, requestId, null);
            if (state.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    /**
     * Advances driver status from {@link MatchStatus#EN_ROUTE} to {@link MatchStatus#ON_TRIP}
     * (passenger picked up, ride in progress).
     *
     * <p>Ownership check: only the request that currently holds the claim can advance the driver.
     * <p>Idempotency: if already {@code ON_TRIP} for the same {@code requestId}, returns {@code true}.
     *
     * @param requestId the request ID holding the claim; must not be blank
     * @return {@code true} if transitioned or already in desired state; {@code false} otherwise
     */
    public boolean transitionToOnTrip(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }

        while (true) {
            State current = state.get();

            // Idempotent: already ON_TRIP for this exact request
            if (current.status() == MatchStatus.ON_TRIP
                    && requestId.equals(current.claimedByRequestId())) {
                return true;
            }

            // Must currently be EN_ROUTE by this exact request
            if (current.status() != MatchStatus.EN_ROUTE
                    || !requestId.equals(current.claimedByRequestId())) {
                return false;
            }

            State next = new State(MatchStatus.ON_TRIP, requestId, null);
            if (state.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    /**
     * Cancels an active claim or en-route trip and releases the driver back to {@link MatchStatus#AVAILABLE}.
     *
     * <p><strong>Cancellation rule:</strong> cancellation is permitted ONLY while the driver is in
     * {@link MatchStatus#CLAIMED} or {@link MatchStatus#EN_ROUTE}. Cancellation is strictly disallowed
     * once the driver has picked up the passenger ({@link MatchStatus#ON_TRIP}).
     *
     * <p><strong>Idempotency & Re-claim Safety:</strong>
     * <ul>
     *   <li>If the driver is currently {@code AVAILABLE} and was last released by this exact {@code requestId},
     *       returns {@code true} (idempotent duplicate cancellation).</li>
     *   <li>If the driver has since been claimed by another request, the ownership check fails
     *       and returns {@code false}, ensuring a repeated cancellation can NEVER release a driver
     *       who has since been claimed by a different request.</li>
     * </ul>
     *
     * @param requestId the request ID cancelling the ride; must not be blank
     * @return {@code true} if successfully cancelled or already cancelled by this request; {@code false} otherwise
     */
    public boolean cancelTrip(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }

        while (true) {
            State current = state.get();

            // Idempotent: already available and this exact request was the one that released it
            if (current.status() == MatchStatus.AVAILABLE
                    && requestId.equals(current.lastReleasedByRequestId())) {
                return true;
            }

            // Cancellation disallowed after pickup (ON_TRIP), or if not claimed by this request
            if ((current.status() != MatchStatus.CLAIMED && current.status() != MatchStatus.EN_ROUTE)
                    || !requestId.equals(current.claimedByRequestId())) {
                return false;
            }

            State released = new State(MatchStatus.AVAILABLE, null, requestId);
            if (state.compareAndSet(current, released)) {
                return true;
            }
        }
    }

    /**
     * Completes an {@link MatchStatus#ON_TRIP} ride and releases the driver back to {@link MatchStatus#AVAILABLE}.
     *
     * <p>Completion rule: allowed ONLY from {@link MatchStatus#ON_TRIP}.
     *
     * <p><strong>Idempotency & Re-claim Safety:</strong>
     * <ul>
     *   <li>If already {@code AVAILABLE} and last released by this exact {@code requestId}, returns {@code true}.</li>
     *   <li>If claimed by another request since, returns {@code false} without touching the active claim.</li>
     * </ul>
     *
     * @param requestId the request ID completing the ride; must not be blank
     * @return {@code true} if completed or already completed by this request; {@code false} otherwise
     */
    public boolean completeTrip(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }

        while (true) {
            State current = state.get();

            // Idempotent: already available and this exact request was the one that released it
            if (current.status() == MatchStatus.AVAILABLE
                    && requestId.equals(current.lastReleasedByRequestId())) {
                return true;
            }

            if (current.status() != MatchStatus.ON_TRIP
                    || !requestId.equals(current.claimedByRequestId())) {
                return false;
            }

            State completed = new State(MatchStatus.AVAILABLE, null, requestId);
            if (state.compareAndSet(current, completed)) {
                return true;
            }
        }
    }

    /**
     * Alias for {@link #cancelTrip(String)} to support releasing a driver claim prior to pickup.
     */
    public boolean releaseToAvailable(String requestId) {
        return cancelTrip(requestId);
    }

    /**
     * Releases a CLAIMED driver back to AVAILABLE, but only if the driver was
     * claimed by the exact {@code requestId} provided. Used for compensation
     * when database persistence fails after matching.
     *
     * @param requestId the request ID that originally claimed this driver; must not be blank
     * @return {@code true} if released or already released by this request; {@code false} otherwise
     */
    public boolean releaseClaim(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }

        State current = state.get();
        if (current.status() != MatchStatus.CLAIMED
                || !requestId.equals(current.claimedByRequestId())) {
            return false;
        }

        State released = new State(MatchStatus.AVAILABLE, null, requestId);
        return state.compareAndSet(current, released);
    }

    /**
     * Restores in-memory driver state from a persistent active trip during lazy recovery
     * (e.g. on driver reconnect or ping processing after service restart).
     *
     * @param requestId  the active trip request ID; must not be blank
     * @param tripStatus the persistent trip status (ASSIGNED, EN_ROUTE, IN_PROGRESS)
     * @return {@code true} if restored or already matching; {@code false} if driver cannot be restored
     */
    public boolean restoreActiveTrip(String requestId, TripStatus tripStatus) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(tripStatus, "tripStatus");
        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }

        MatchStatus targetStatus = switch (tripStatus) {
            case ASSIGNED    -> MatchStatus.CLAIMED;
            case EN_ROUTE    -> MatchStatus.EN_ROUTE;
            case IN_PROGRESS -> MatchStatus.ON_TRIP;
            case COMPLETED, CANCELLED -> null;
        };

        if (targetStatus == null) {
            return false;
        }

        while (true) {
            State current = state.get();
            if (current.status() == targetStatus && requestId.equals(current.claimedByRequestId())) {
                return true;
            }
            if (current.status() != MatchStatus.AVAILABLE) {
                return false;
            }
            State restored = new State(targetStatus, requestId, null);
            if (state.compareAndSet(current, restored)) {
                return true;
            }
        }
    }

    public MatchStatus getStatus() {
        return state.get().status();
    }

    public String getClaimedByRequestId() {
        return state.get().claimedByRequestId();
    }

    public String getLastReleasedByRequestId() {
        return state.get().lastReleasedByRequestId();
    }
}
