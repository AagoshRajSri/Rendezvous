
package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.RideRequest;

@FunctionalInterface
public interface CostFunction {
    double calculate(Driver driver, RideRequest request);
}
