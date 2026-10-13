package com.aagosh.dispatch.api;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.domain.RideRequest;
import com.aagosh.dispatch.matching.CostFunction;
import com.aagosh.dispatch.matching.DispatchCostConfig;
import com.aagosh.dispatch.matching.DispatchCostFunction;
import com.aagosh.dispatch.matching.Matcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * REST controller for ride dispatch matching.
 */
@RestController
public class RideController {

    private final Matcher matcher;
    private final DriverRegistry driverRegistry;
    private final DispatchCostConfig costConfig;

    @Autowired
    public RideController(Matcher matcher, DriverRegistry driverRegistry, DispatchCostConfig costConfig) {
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.driverRegistry = Objects.requireNonNull(driverRegistry, "driverRegistry");
        this.costConfig = Objects.requireNonNull(costConfig, "costConfig");
    }

    public RideController(Matcher matcher, DriverRegistry driverRegistry) {
        this(matcher, driverRegistry, DispatchCostConfig.DEFAULT);
    }

    @PostMapping("/request")
    public ResponseEntity<RideResponse> requestRide(@RequestBody RideRequestDto requestDto) {
        if (requestDto == null) {
            throw new InvalidRequestException("Request body must not be null");
        }
        requestDto.validate();

        String requestId = requestDto.resolveId();
        GeoPoint pickupPoint = new GeoPoint(requestDto.latitude(), requestDto.longitude());
        RideRequest domainRequest = new RideRequest(requestId, pickupPoint);

        List<Driver> drivers = driverRegistry.snapshot();
        CostFunction costFunction = new DispatchCostFunction(costConfig, List.of(domainRequest));

        List<Assignment> assignments = matcher.match(List.of(domainRequest), drivers, costFunction);

        if (assignments.isEmpty()) {
            return ResponseEntity.ok(new RideResponse(
                    requestId,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "No available driver could be matched"
            ));
        }

        Assignment assignment = assignments.get(0);
        Driver matchedDriver = assignment.driver();

        // Enforce Matcher contract defensively: verify driver was successfully claimed for this request
        boolean isClaimed = matchedDriver.state().getStatus() == MatchStatus.CLAIMED
                && domainRequest.id().equals(matchedDriver.state().getClaimedByRequestId());

        if (!isClaimed) {
            return ResponseEntity.ok(new RideResponse(
                    requestId,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "No available driver could be matched"
            ));
        }

        return ResponseEntity.ok(new RideResponse(
                requestId,
                true,
                matchedDriver.id(),
                matchedDriver.location().latitude(),
                matchedDriver.location().longitude(),
                matchedDriver.state().getStatus().name(),
                assignment.cost(),
                "Driver assigned successfully"
        ));
    }
}
