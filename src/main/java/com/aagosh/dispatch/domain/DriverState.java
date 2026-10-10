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

    public MatchStatus getStatus() {
        return state.get().status();
    }

    public String getClaimedByRequestId() {
        return state.get().claimedByRequestId();
    }
}
