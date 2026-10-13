package com.aagosh.dispatch.domain;

import java.util.Objects;

public record Assignment(
        RideRequest request,
        Driver driver,
        double cost
) {
    public Assignment {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(driver, "driver");

        if (!Double.isFinite(cost) || cost < 0) {
            throw new IllegalArgumentException(
                    "Cost must be finite and non-negative");
        }
    }
}
