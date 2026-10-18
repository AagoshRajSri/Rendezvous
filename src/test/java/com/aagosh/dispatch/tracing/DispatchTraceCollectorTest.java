package com.aagosh.dispatch.tracing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DispatchTraceCollectorTest {

    @Test
    @DisplayName("NoOp collector discards events and returns null without allocations")
    void noOpCollectorBehavior() {
        DispatchTraceCollector collector = DispatchTraceCollector.noop("req-noop");

        assertThat(collector.isEnabled()).isFalse();
        assertThat(collector.requestId()).isEqualTo("req-noop");

        // Safe no-ops
        collector.setAlgorithm("Any");
        collector.setPickupLocation(10.0, 20.0);
        collector.setTotalFleetSize(100);
        collector.addStage(new SpatialStage(0, "STAGE", 16, null, null, null, null, 1, 1, 1));
        collector.addCandidate(new TraceCandidate("D1", 10.0, 20.0, 1.0, null, null, null, 0.5, 1, CandidateOutcome.MATCHED, null));
        collector.recordMatch("D1", "Matched");
        collector.finish(100_000L);

        assertThat(collector.build()).isNull();
    }

    @Test
    @DisplayName("Factory returns noop collector when config.enabled is false")
    void factoryReturnsNoopWhenDisabled() {
        DispatchTraceConfig disabledConfig = new DispatchTraceConfig(32, 16, 200, Duration.ofMinutes(10), false);
        DispatchTraceCollector collector = DispatchTraceCollector.create("req-disabled", disabledConfig);

        assertThat(collector.isEnabled()).isFalse();
        assertThat(collector.build()).isNull();
    }

    @Test
    @DisplayName("Bounded collector records full trace and enforces stage and candidate caps")
    void boundedCollectorCaps() {
        // Limit to max 2 stages and 3 candidates
        DispatchTraceConfig config = new DispatchTraceConfig(3, 2, 200, Duration.ofMinutes(10), true);
        DispatchTraceCollector collector = DispatchTraceCollector.create("req-capped", config);

        assertThat(collector.isEnabled()).isTrue();
        collector.setAlgorithm("GreedyMatcher (Spatial QuadTree)");
        collector.setPickupLocation(17.3850, 78.4867);
        collector.setTotalFleetSize(15);

        // Add 4 stages (cap is 2)
        collector.addStage(new SpatialStage(0, "INITIAL_WINDOW", 16, null, null, null, null, 2, 1, 2));
        collector.addStage(new SpatialStage(1, "EXPANDED_WINDOW", 64, null, null, null, null, 4, 2, 4));
        collector.addStage(new SpatialStage(2, "EXHAUSTIVE_PASS", 100, null, null, null, null, 6, 3, 6));
        collector.addStage(new SpatialStage(3, "EXTRA_STAGE", 100, null, null, null, null, 8, 4, 8));

        // Add 5 candidates (cap is 3)
        for (int i = 1; i <= 5; i++) {
            collector.addCandidate(new TraceCandidate(
                    "D" + i, 17.38 + i * 0.01, 78.48 + i * 0.01, i * 0.5,
                    0.1 * i, 0.1, 0.0, 0.2 * i, i,
                    i == 1 ? CandidateOutcome.MATCHED : CandidateOutcome.OUTCOMPETED,
                    i == 1 ? null : "Higher cost"
            ));
        }

        collector.recordMatch("D1", "Matched closest candidate D1");
        collector.finish(1_250_000L);

        DispatchTrace trace = collector.build();
        assertThat(trace).isNotNull();
        assertThat(trace.requestId()).isEqualTo("req-capped");
        assertThat(trace.algorithm()).isEqualTo("GreedyMatcher (Spatial QuadTree)");
        assertThat(trace.pickupLatitude()).isEqualTo(17.3850);
        assertThat(trace.pickupLongitude()).isEqualTo(78.4867);
        assertThat(trace.totalFleetSize()).isEqualTo(15);
        assertThat(trace.matched()).isTrue();
        assertThat(trace.winnerDriverId()).isEqualTo("D1");
        assertThat(trace.decisionSummary()).isEqualTo("Matched closest candidate D1");
        assertThat(trace.durationNanos()).isEqualTo(1_250_000L);

        // Capped verification
        assertThat(trace.stages()).hasSize(2);
        assertThat(trace.stages().get(0).stageType()).isEqualTo("INITIAL_WINDOW");
        assertThat(trace.stages().get(1).stageType()).isEqualTo("EXPANDED_WINDOW");

        assertThat(trace.candidates()).hasSize(3);
        assertThat(trace.candidates().get(0).driverId()).isEqualTo("D1");
        assertThat(trace.candidates().get(1).driverId()).isEqualTo("D2");
        assertThat(trace.candidates().get(2).driverId()).isEqualTo("D3");
    }

    @Test
    @DisplayName("Collector safely isolates unexpected nulls and errors without failing")
    void failureIsolationInCollector() {
        DispatchTraceCollector collector = DispatchTraceCollector.create("req-safe", DispatchTraceConfig.DEFAULT);

        assertThatCode(() -> {
            collector.setAlgorithm(null);
            collector.addStage(null);
            collector.addCandidate(null);
            collector.recordMatch(null, null);
            collector.recordNoDrivers(null);
            collector.finish(-100);
        }).doesNotThrowAnyException();

        DispatchTrace trace = collector.build();
        assertThat(trace).isNotNull();
        assertThat(trace.requestId()).isEqualTo("req-safe");
    }
}
