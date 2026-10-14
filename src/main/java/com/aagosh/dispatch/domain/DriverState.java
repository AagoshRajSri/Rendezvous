package com.aagosh.dispatch.domain;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class DriverState {

    private record State(
            MatchStatus status,
            String claimedByRequestId
    ) {}

    private final AtomicReference<State> state =
            new AtomicReference<>(
                    new State(MatchStatus.AVAILABLE, null));

    public boolean tryClaim(String requestId) {
        Objects.requireNonNull(requestId, "requestId");

        if (requestId.isBlank()) {
            throw new IllegalArgumentException(
                    "requestId must not be blank");
        }

        State current = state.get();

        if (current.status() != MatchStatus.AVAILABLE) {
            return false;
        }

        State claimed = new State(
                MatchStatus.CLAIMED, requestId);

        return state.compareAndSet(current, claimed);
    }

    /**
     * Releases a CLAIMED driver back to AVAILABLE, but only if the driver was
     * claimed by the exact {@code requestId} provided.
     *
     * <p>This ownership check ensures that a late compensation call from one
     * request cannot accidentally free a driver already re-claimed by a
     * different request.
     *
     * @param requestId the request ID that originally claimed this driver
     * @return {@code true} if the release CAS succeeded; {@code false} if the
     *         driver was not currently claimed by this request (already released,
     *         re-claimed, or claimed by someone else)
     */
    public boolean releaseClaim(String requestId) {
        Objects.requireNonNull(requestId, "requestId");

        State current = state.get();
        if (current.status() != MatchStatus.CLAIMED
                || !requestId.equals(current.claimedByRequestId())) {
            return false;
        }

        State released = new State(MatchStatus.AVAILABLE, null);
        return state.compareAndSet(current, released);
    }

    public MatchStatus getStatus() {
        return state.get().status();
    }

    public String getClaimedByRequestId() {
        return state.get().claimedByRequestId();
    }
}
