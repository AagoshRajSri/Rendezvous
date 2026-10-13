
package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.RideRequest;

import java.util.List;

public interface Matcher {
    List<Assignment> match(
            List<RideRequest> requests,
            List<Driver> drivers,
            CostFunction costFunction
    );
}
