package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.RideRequest;
import com.aagosh.dispatch.tracing.CandidateOutcome;
import com.aagosh.dispatch.tracing.DispatchTrace;
import com.aagosh.dispatch.tracing.DispatchTraceCollector;
import com.aagosh.dispatch.tracing.DispatchTraceConfig;
import com.aagosh.dispatch.tracing.TraceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuctionMatcherTracingTest {

    private final AuctionMatcher matcher = new AuctionMatcher();
    private final CostFunction simpleCost = (driver, req) -> driver.location().distanceToKm(req.pickupLocation());

    @Test
    @DisplayName("Traces global auction match with candidate ranking, cost breakdown, and empty spatial stages")
    void tracesGlobalAuctionMatch() {
        RideRequest req1 = new RideRequest("REQ-A", new GeoPoint(17.3850, 78.4867));
        RideRequest req2 = new RideRequest("REQ-B", new GeoPoint(17.3900, 78.4900));

        Driver d1 = new Driver("D1", new GeoPoint(17.3860, 78.4870), new DriverState());
        Driver d2 = new Driver("D2", new GeoPoint(17.3910, 78.4910), new DriverState());
        Driver d3 = new Driver("D3", new GeoPoint(17.4000, 78.5000), new DriverState());

        DispatchCostConfig costConfig = DispatchCostConfig.DEFAULT;
        DispatchCostFunction costFn = new DispatchCostFunction(costConfig, List.of(req1, req2));

        DispatchTraceCollector colA = DispatchTraceCollector.create("REQ-A", DispatchTraceConfig.DEFAULT);
        DispatchTraceCollector colB = DispatchTraceCollector.create("REQ-B", DispatchTraceConfig.DEFAULT);

        List<Assignment> assignments = matcher.match(
                List.of(req1, req2),
                List.of(d1, d2, d3),
                costFn,
                reqId -> "REQ-A".equals(reqId) ? colA : colB
        );

        assertThat(assignments).hasSize(2);

        DispatchTrace traceA = colA.build();
        assertThat(traceA).isNotNull();
        assertThat(traceA.requestId()).isEqualTo("REQ-A");
        assertThat(traceA.algorithm()).isEqualTo("AuctionMatcher (Bertsekas)");
        assertThat(traceA.matched()).isTrue();
        assertThat(traceA.totalFleetSize()).isEqualTo(3);
        assertThat(traceA.durationNanos()).isGreaterThan(0L);

        // Crucial invariant: AuctionMatcher does NOT perform QuadTree traversal, so stages must be empty
        assertThat(traceA.stages())
                .as("AuctionMatcher must not fabricate QuadTree spatial stages")
                .isEmpty();

        // Candidates verification
        assertThat(traceA.candidates()).hasSize(3);
        String winnerId = traceA.winnerDriverId();
        assertThat(winnerId).isNotNull();

        TraceCandidate winnerCandidate = traceA.candidates().stream()
                .filter(c -> c.driverId().equals(winnerId))
                .findFirst()
                .orElseThrow();
        assertThat(winnerCandidate.outcome()).isEqualTo(CandidateOutcome.MATCHED);
        assertThat(winnerCandidate.etaCost()).isNotNull();
        assertThat(winnerCandidate.densityCost()).isNotNull();
        assertThat(winnerCandidate.totalCost()).isNotNull();

        assertThat(traceA.candidates().stream().filter(c -> !c.driverId().equals(winnerId)))
                .allMatch(c -> c.outcome() == CandidateOutcome.OUTCOMPETED
                        && c.rejectionReason().contains("Outcompeted in global bipartite utility assignment"));

        DispatchTrace traceB = colB.build();
        assertThat(traceB).isNotNull();
        assertThat(traceB.matched()).isTrue();
        assertThat(traceB.stages()).isEmpty();
    }

    @Test
    @DisplayName("Traces claim loss when auction-winning driver fails atomic tryClaim CAS")
    void tracesClaimLossInAuction() {
        RideRequest request = new RideRequest("REQ-AUC-CAS", new GeoPoint(17.3850, 78.4867));
        Driver driver = new Driver("D-CONFLICT", new GeoPoint(17.3860, 78.4870), new DriverState());

        // Simulate concurrent claim occurring during cost evaluation
        CostFunction concurrentClaimCost = (d, req) -> {
            d.state().tryClaim("CONCURRENT-EXTERNAL-REQ");
            return 0.5;
        };

        DispatchTraceCollector collector = DispatchTraceCollector.create("REQ-AUC-CAS", DispatchTraceConfig.DEFAULT);
        List<Assignment> assignments = matcher.match(List.of(request), List.of(driver), concurrentClaimCost, collector);

        assertThat(assignments).isEmpty();

        DispatchTrace trace = collector.build();
        assertThat(trace).isNotNull();
        assertThat(trace.matched()).isFalse();
        assertThat(trace.decisionSummary()).contains("atomic claim failed");

        assertThat(trace.candidates()).hasSize(1);
        assertThat(trace.candidates().get(0).driverId()).isEqualTo("D-CONFLICT");
        assertThat(trace.candidates().get(0).outcome()).isEqualTo(CandidateOutcome.CLAIM_LOST);
        assertThat(trace.candidates().get(0).rejectionReason()).contains("Atomic claim failed");
    }

    @Test
    @DisplayName("Traces empty fleet and all-unavailable fleet appropriately")
    void tracesEmptyAndUnavailableFleet() {
        RideRequest request = new RideRequest("REQ-EMPTY", new GeoPoint(17.3850, 78.4867));
        DispatchTraceCollector colEmpty = DispatchTraceCollector.create("REQ-EMPTY", DispatchTraceConfig.DEFAULT);

        // Empty fleet
        matcher.match(List.of(request), List.of(), simpleCost, colEmpty);
        DispatchTrace traceEmpty = colEmpty.build();
        assertThat(traceEmpty).isNotNull();
        assertThat(traceEmpty.matched()).isFalse();
        assertThat(traceEmpty.totalFleetSize()).isZero();
        assertThat(traceEmpty.decisionSummary()).contains("Fleet is empty");

        // All unavailable fleet
        DriverState busyState = new DriverState();
        busyState.tryClaim("PRIOR-CLAIM");
        Driver busyDriver = new Driver("D-BUSY", new GeoPoint(17.3860, 78.4870), busyState);

        DispatchTraceCollector colUnavailable = DispatchTraceCollector.create("REQ-UNAVAIL", DispatchTraceConfig.DEFAULT);
        RideRequest reqUnavail = new RideRequest("REQ-UNAVAIL", new GeoPoint(17.3850, 78.4867));

        matcher.match(List.of(reqUnavail), List.of(busyDriver), simpleCost, colUnavailable);
        DispatchTrace traceUnavail = colUnavailable.build();
        assertThat(traceUnavail).isNotNull();
        assertThat(traceUnavail.matched()).isFalse();
        assertThat(traceUnavail.candidates()).hasSize(1);
        assertThat(traceUnavail.candidates().get(0).outcome()).isEqualTo(CandidateOutcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("Matching assignments are strictly identical with and without tracing in AuctionMatcher")
    void deterministicObservationalInvariance() {
        RideRequest req1 = new RideRequest("REQ-1", new GeoPoint(17.3850, 78.4867));
        RideRequest req2 = new RideRequest("REQ-2", new GeoPoint(17.3900, 78.4900));

        List<Driver> driversA = new ArrayList<>();
        List<Driver> driversB = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            driversA.add(new Driver("DRV-" + i, new GeoPoint(17.38 + i * 0.005, 78.48 + i * 0.005), new DriverState()));
            driversB.add(new Driver("DRV-" + i, new GeoPoint(17.38 + i * 0.005, 78.48 + i * 0.005), new DriverState()));
        }

        List<Assignment> uninstrumented = matcher.match(List.of(req1, req2), driversA, simpleCost);

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
    }

    @Test
    @DisplayName("Enforces configured candidate caps in AuctionMatcher")
    void respectsConfiguredCandidateCaps() {
        RideRequest request = new RideRequest("REQ-CAPPED-AUC", new GeoPoint(17.3850, 78.4867));

        List<Driver> drivers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            drivers.add(new Driver("DRV-" + i, new GeoPoint(17.38 + i * 0.005, 78.48 + i * 0.005), new DriverState()));
        }

        // Limit candidate cap to 4
        DispatchTraceConfig cappedConfig = new DispatchTraceConfig(4, 5, 100, Duration.ofMinutes(5), true);
        DispatchTraceCollector collector = DispatchTraceCollector.create("REQ-CAPPED-AUC", cappedConfig);

        matcher.match(List.of(request), drivers, simpleCost, collector);

        DispatchTrace trace = collector.build();
        assertThat(trace).isNotNull();
        assertThat(trace.candidates()).hasSize(4);
    }

    @Test
    @DisplayName("Throwing collector provider is safely isolated without disrupting AuctionMatcher matching")
    void throwingCollectorProviderDoesNotDisruptMatching() {
        RideRequest request = new RideRequest("REQ-AUC-PROV-FAIL", new GeoPoint(17.3850, 78.4867));
        Driver driver = new Driver("DRV-SAFE", new GeoPoint(17.3860, 78.4870), new DriverState());

        List<Assignment> assignments = matcher.match(
                List.of(request),
                List.of(driver),
                simpleCost,
                reqId -> { throw new RuntimeException("Simulated auction collector provider failure"); }
        );

        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).driver().id()).isEqualTo("DRV-SAFE");
    }

    @Test
    @DisplayName("Failing trace collector operations do not disrupt committed assignments or subsequent requests in batch")
    void traceFailureDoesNotDisruptCommittedAssignments() {
        RideRequest req1 = new RideRequest("REQ-1", new GeoPoint(17.3850, 78.4867));
        RideRequest req2 = new RideRequest("REQ-2", new GeoPoint(17.3900, 78.4900));

        Driver d1 = new Driver("D1", new GeoPoint(17.3860, 78.4870), new DriverState());
        Driver d2 = new Driver("D2", new GeoPoint(17.3910, 78.4910), new DriverState());

        // Collector that throws on candidate recording
        DispatchTraceCollector failingCollector = new DispatchTraceCollector() {
            @Override public String requestId() { return "REQ-1"; }
            @Override public boolean isEnabled() { return true; }
            @Override public void setAlgorithm(String algorithm) {}
            @Override public void setPickupLocation(double lat, double lon) {}
            @Override public void setTotalFleetSize(int size) {}
            @Override public void addStage(com.aagosh.dispatch.tracing.SpatialStage s) {}
            @Override public void addCandidate(com.aagosh.dispatch.tracing.TraceCandidate c) {
                throw new RuntimeException("Simulated trace recording crash");
            }
            @Override public void recordMatch(String id, String summary) {}
            @Override public void recordNoDrivers(String summary) {}
            @Override public void finish(long durationNanos) {}
            @Override public DispatchTrace build() { return null; }
        };

        DispatchTraceCollector normalCollector = DispatchTraceCollector.create("REQ-2", DispatchTraceConfig.DEFAULT);

        List<Assignment> assignments = matcher.match(
                List.of(req1, req2),
                List.of(d1, d2),
                simpleCost,
                reqId -> "REQ-1".equals(reqId) ? failingCollector : normalCollector
        );

        assertThat(assignments).hasSize(2);
        assertThat(assignments).extracting(a -> a.request().id()).containsExactlyInAnyOrder("REQ-1", "REQ-2");
        assertThat(d1.state().getStatus()).isEqualTo(com.aagosh.dispatch.domain.MatchStatus.CLAIMED);
        assertThat(d2.state().getStatus()).isEqualTo(com.aagosh.dispatch.domain.MatchStatus.CLAIMED);

        DispatchTrace trace2 = normalCollector.build();
        assertThat(trace2).isNotNull();
        assertThat(trace2.matched()).isTrue();
    }
}
