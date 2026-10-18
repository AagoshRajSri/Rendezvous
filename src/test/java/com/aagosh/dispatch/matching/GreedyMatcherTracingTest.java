package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.domain.RideRequest;
import com.aagosh.dispatch.tracing.CandidateOutcome;
import com.aagosh.dispatch.tracing.DispatchTrace;
import com.aagosh.dispatch.tracing.DispatchTraceCollector;
import com.aagosh.dispatch.tracing.DispatchTraceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GreedyMatcherTracingTest {

    private final GreedyMatcher matcher = new GreedyMatcher();
    private final CostFunction simpleCost = (driver, req) -> driver.location().distanceToKm(req.pickupLocation());

    @Test
    @DisplayName("Traces successful match with QuadTree stages, candidate ranking, and cost breakdown")
    void tracesSuccessfulMatch() {
        RideRequest request = new RideRequest("REQ-1", new GeoPoint(17.3850, 78.4867));

        Driver d1 = new Driver("D1", new GeoPoint(17.3860, 78.4870), new DriverState());
        Driver d2 = new Driver("D2", new GeoPoint(17.3900, 78.4900), new DriverState());
        Driver d3 = new Driver("D3", new GeoPoint(17.4000, 78.5000), new DriverState());

        DispatchCostConfig costConfig = DispatchCostConfig.DEFAULT;
        // Empty unmatched requests list ensures density cost is 0 for all candidates so distance dictates cost
        DispatchCostFunction costFn = new DispatchCostFunction(costConfig, List.of());

        DispatchTraceCollector collector = DispatchTraceCollector.create("REQ-1", DispatchTraceConfig.DEFAULT);

        List<Assignment> assignments = matcher.match(List.of(request), List.of(d1, d2, d3), costFn, collector);

        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).driver().id()).isEqualTo("D1");

        DispatchTrace trace = collector.build();
        assertThat(trace).isNotNull();
        assertThat(trace.requestId()).isEqualTo("REQ-1");
        assertThat(trace.algorithm()).isEqualTo("GreedyMatcher (Spatial QuadTree)");
        assertThat(trace.matched()).isTrue();
        assertThat(trace.winnerDriverId()).isEqualTo("D1");
        assertThat(trace.totalFleetSize()).isEqualTo(3);
        assertThat(trace.decisionSummary()).contains("Matched driver D1");
        assertThat(trace.durationNanos()).isGreaterThan(0L);

        // Stages verification
        assertThat(trace.stages()).isNotEmpty();
        assertThat(trace.stages().get(0).stageType()).isEqualTo("INITIAL_WINDOW");
        assertThat(trace.stages().get(0).visitedNodeCount()).isGreaterThan(0);
        assertThat(trace.stages().get(0).minLatitude()).isNotNull();

        // Candidates verification
        assertThat(trace.candidates()).hasSize(3);
        assertThat(trace.candidates().get(0).driverId()).isEqualTo("D1");
        assertThat(trace.candidates().get(0).outcome()).isEqualTo(CandidateOutcome.MATCHED);
        assertThat(trace.candidates().get(0).rank()).isEqualTo(1);
        assertThat(trace.candidates().get(0).etaCost()).isNotNull();
        assertThat(trace.candidates().get(0).densityCost()).isNotNull();
        assertThat(trace.candidates().get(0).totalCost()).isNotNull();

        assertThat(trace.candidates().get(1).driverId()).isEqualTo("D2");
        assertThat(trace.candidates().get(1).outcome()).isEqualTo(CandidateOutcome.OUTCOMPETED);
        assertThat(trace.candidates().get(1).rejectionReason()).contains("Outcompeted by lower-cost candidate D1");

        assertThat(trace.candidates().get(2).driverId()).isEqualTo("D3");
        assertThat(trace.candidates().get(2).outcome()).isEqualTo(CandidateOutcome.OUTCOMPETED);
    }

    @Test
    @DisplayName("Traces QuadTree window expansion when initial window candidates are unavailable")
    void tracesWindowExpansion() {
        RideRequest request = new RideRequest("REQ-EXPAND", new GeoPoint(17.3850, 78.4867));

        List<Driver> drivers = new ArrayList<>();
        // Place the 16 closest drivers adjacent to pickup and mark them unavailable
        for (int i = 0; i < 16; i++) {
            DriverState state = new DriverState();
            state.tryClaim("PRIOR-REQ-" + i);
            drivers.add(new Driver("DRV-UNAVAIL-" + i, new GeoPoint(17.3851 + i * 0.001, 78.4868 + i * 0.001), state));
        }

        // Place available drivers further away, requiring window expansion beyond initial 16
        drivers.add(new Driver("DRV-AVAIL-WINNER", new GeoPoint(17.4500, 78.5500), new DriverState()));
        for (int i = 1; i <= 3; i++) {
            drivers.add(new Driver("DRV-AVAIL-" + i, new GeoPoint(17.4600 + i * 0.01, 78.5600 + i * 0.01), new DriverState()));
        }

        DispatchTraceCollector collector = DispatchTraceCollector.create("REQ-EXPAND", DispatchTraceConfig.DEFAULT);
        List<Assignment> assignments = matcher.match(List.of(request), drivers, simpleCost, collector);

        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).driver().id()).isEqualTo("DRV-AVAIL-WINNER");

        DispatchTrace trace = collector.build();
        assertThat(trace).isNotNull();
        assertThat(trace.matched()).isTrue();
        assertThat(trace.winnerDriverId()).isEqualTo("DRV-AVAIL-WINNER");

        // Should have at least 2 stages: INITIAL_WINDOW (16) and EXPANDED_WINDOW or EXHAUSTIVE_PASS (20)
        assertThat(trace.stages()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(trace.stages().get(0).stageType()).isEqualTo("INITIAL_WINDOW");
        assertThat(trace.stages().get(1).stageType()).isIn("EXPANDED_WINDOW", "EXHAUSTIVE_PASS");

        // The first 16 candidates must be recorded as UNAVAILABLE
        for (int i = 0; i < 16; i++) {
            assertThat(trace.candidates().get(i).outcome()).isEqualTo(CandidateOutcome.UNAVAILABLE);
        }
        // Candidate 17 is DRV-AVAIL-WINNER and MATCHED
        assertThat(trace.candidates().get(16).driverId()).isEqualTo("DRV-AVAIL-WINNER");
        assertThat(trace.candidates().get(16).outcome()).isEqualTo(CandidateOutcome.MATCHED);
    }

    @Test
    @DisplayName("Traces claim loss when atomic tryClaim CAS fails")
    void tracesClaimLoss() {
        RideRequest request = new RideRequest("REQ-CLAIM-FAIL", new GeoPoint(17.3850, 78.4867));
        Driver driver = new Driver("D-BUSY", new GeoPoint(17.3860, 78.4870), new DriverState());

        // Simulate concurrent claim occurring during candidate evaluation
        CostFunction concurrentClaimCost = (d, req) -> {
            d.state().tryClaim("CONCURRENT-CLAIMANT");
            return 0.5;
        };

        DispatchTraceCollector collector = DispatchTraceCollector.create("REQ-CLAIM-FAIL", DispatchTraceConfig.DEFAULT);
        List<Assignment> assignments = matcher.match(List.of(request), List.of(driver), concurrentClaimCost, collector);

        assertThat(assignments).isEmpty();

        DispatchTrace trace = collector.build();
        assertThat(trace).isNotNull();
        assertThat(trace.matched()).isFalse();
        assertThat(trace.winnerDriverId()).isNull();
        assertThat(trace.decisionSummary()).contains("Exhausted all");

        assertThat(trace.candidates()).hasSize(1);
        assertThat(trace.candidates().get(0).driverId()).isEqualTo("D-BUSY");
        assertThat(trace.candidates().get(0).outcome()).isEqualTo(CandidateOutcome.CLAIM_LOST);
        assertThat(trace.candidates().get(0).rejectionReason()).contains("Atomic claim failed");
    }

    @Test
    @DisplayName("Traces empty fleet without errors")
    void tracesEmptyFleet() {
        RideRequest request = new RideRequest("REQ-EMPTY", new GeoPoint(17.3850, 78.4867));
        DispatchTraceCollector collector = DispatchTraceCollector.create("REQ-EMPTY", DispatchTraceConfig.DEFAULT);

        List<Assignment> assignments = matcher.match(List.of(request), List.of(), simpleCost, collector);

        assertThat(assignments).isEmpty();

        DispatchTrace trace = collector.build();
        assertThat(trace).isNotNull();
        assertThat(trace.matched()).isFalse();
        assertThat(trace.totalFleetSize()).isZero();
        assertThat(trace.decisionSummary()).contains("Fleet is empty");
    }

    @Test
    @DisplayName("Enforces configured stage and candidate caps")
    void respectsConfiguredCaps() {
        RideRequest request = new RideRequest("REQ-CAPPED", new GeoPoint(17.3850, 78.4867));

        // Fleet of 25 drivers
        List<Driver> drivers = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            drivers.add(new Driver("D" + i, new GeoPoint(17.30 + i * 0.01, 78.40 + i * 0.01), new DriverState()));
        }

        // Limit to max 1 stage and 3 candidates
        DispatchTraceConfig cappedConfig = new DispatchTraceConfig(3, 1, 100, Duration.ofMinutes(5), true);
        DispatchTraceCollector collector = DispatchTraceCollector.create("REQ-CAPPED", cappedConfig);

        matcher.match(List.of(request), drivers, simpleCost, collector);

        DispatchTrace trace = collector.build();
        assertThat(trace).isNotNull();
        assertThat(trace.stages()).hasSize(1);
        assertThat(trace.candidates()).hasSize(3);
    }

    @Test
    @DisplayName("Matching behavior and assignments are strictly identical with and without tracing")
    void deterministicObservationalInvariance() {
        RideRequest req1 = new RideRequest("REQ-1", new GeoPoint(17.3850, 78.4867));
        RideRequest req2 = new RideRequest("REQ-2", new GeoPoint(17.3900, 78.4900));

        // 10 drivers
        List<Driver> driversA = new ArrayList<>();
        List<Driver> driversB = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            driversA.add(new Driver("DRV-" + i, new GeoPoint(17.38 + i * 0.005, 78.48 + i * 0.005), new DriverState()));
            driversB.add(new Driver("DRV-" + i, new GeoPoint(17.38 + i * 0.005, 78.48 + i * 0.005), new DriverState()));
        }

        // Uninstrumented call
        List<Assignment> uninstrumented = matcher.match(List.of(req1, req2), driversA, simpleCost);

        // Instrumented call with collectors
        DispatchTraceCollector col1 = DispatchTraceCollector.create("REQ-1", DispatchTraceConfig.DEFAULT);
        DispatchTraceCollector col2 = DispatchTraceCollector.create("REQ-2", DispatchTraceConfig.DEFAULT);

        List<Assignment> instrumented = matcher.match(
                List.of(req1, req2),
                driversB,
                simpleCost,
                reqId -> "REQ-1".equals(reqId) ? col1 : col2
        );

        assertThat(instrumented).hasSameSizeAs(uninstrumented);
        for (int i = 0; i < uninstrumented.size(); i++) {
            Assignment a = uninstrumented.get(i);
            Assignment b = instrumented.get(i);
            assertThat(b.request().id()).isEqualTo(a.request().id());
            assertThat(b.driver().id()).isEqualTo(a.driver().id());
            assertThat(b.cost()).isEqualTo(a.cost());
        }

        assertThat(col1.build()).isNotNull();
        assertThat(col2.build()).isNotNull();
    }

    @Test
    @DisplayName("Throwing collector provider is safely isolated without disrupting GreedyMatcher matching")
    void throwingCollectorProviderDoesNotDisruptMatching() {
        RideRequest request = new RideRequest("REQ-PROV-FAIL", new GeoPoint(17.3850, 78.4867));
        Driver driver = new Driver("DRV-SAFE", new GeoPoint(17.3860, 78.4870), new DriverState());

        List<Assignment> assignments = matcher.match(
                List.of(request),
                List.of(driver),
                simpleCost,
                reqId -> { throw new RuntimeException("Simulated collector provider failure"); }
        );

        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).driver().id()).isEqualTo("DRV-SAFE");
    }

    @Test
    @DisplayName("Failing trace collector operations are safely suppressed without disrupting GreedyMatcher matching")
    void failingCollectorDoesNotDisruptMatching() {
        RideRequest request = new RideRequest("REQ-COL-FAIL", new GeoPoint(17.3850, 78.4867));
        Driver driver = new Driver("DRV-SAFE", new GeoPoint(17.3860, 78.4870), new DriverState());

        // Collector that throws on all mutation calls
        DispatchTraceCollector failingCollector = new DispatchTraceCollector() {
            @Override public String requestId() { return "REQ-COL-FAIL"; }
            @Override public boolean isEnabled() { return true; }
            @Override public void setAlgorithm(String algorithm) { throw new RuntimeException("fail"); }
            @Override public void setPickupLocation(double lat, double lon) { throw new RuntimeException("fail"); }
            @Override public void setTotalFleetSize(int size) { throw new RuntimeException("fail"); }
            @Override public void addStage(com.aagosh.dispatch.tracing.SpatialStage s) { throw new RuntimeException("fail"); }
            @Override public void addCandidate(com.aagosh.dispatch.tracing.TraceCandidate c) { throw new RuntimeException("fail"); }
            @Override public void recordMatch(String id, String summary) { throw new RuntimeException("fail"); }
            @Override public void recordNoDrivers(String summary) { throw new RuntimeException("fail"); }
            @Override public void finish(long durationNanos) { throw new RuntimeException("fail"); }
            @Override public DispatchTrace build() { return null; }
        };

        List<Assignment> assignments = matcher.match(
                List.of(request),
                List.of(driver),
                simpleCost,
                failingCollector
        );

        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).driver().id()).isEqualTo("DRV-SAFE");
    }
}
