package com.aagosh.dispatch.tracing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DispatchTraceModelTest {

    @Test
    @DisplayName("TraceCandidate validates required fields and non-negative values")
    void traceCandidateValidation() {
        TraceCandidate candidate = new TraceCandidate(
                "D1", 17.3850, 78.4867, 1.25,
                0.2, 0.1, 0.0, 0.3,
                1, CandidateOutcome.MATCHED, null
        );

        assertThat(candidate.driverId()).isEqualTo("D1");
        assertThat(candidate.distanceKm()).isEqualTo(1.25);
        assertThat(candidate.totalCost()).isEqualTo(0.3);
        assertThat(candidate.outcome()).isEqualTo(CandidateOutcome.MATCHED);

        assertThatThrownBy(() -> new TraceCandidate(
                null, 17.0, 78.0, 1.0, null, null, null, null, 1, CandidateOutcome.MATCHED, null
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new TraceCandidate(
                "   ", 17.0, 78.0, 1.0, null, null, null, null, 1, CandidateOutcome.MATCHED, null
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new TraceCandidate(
                "D1", 17.0, 78.0, -0.5, null, null, null, null, 1, CandidateOutcome.MATCHED, null
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new TraceCandidate(
                "D1", 17.0, 78.0, 1.0, null, null, null, null, -1, CandidateOutcome.MATCHED, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SpatialStage validates stage index and non-negative counts")
    void spatialStageValidation() {
        SpatialStage stage = new SpatialStage(
                0, "INITIAL_WINDOW", 16,
                17.0, 78.0, 18.0, 79.0,
                4, 2, 3
        );

        assertThat(stage.stageIndex()).isEqualTo(0);
        assertThat(stage.stageType()).isEqualTo("INITIAL_WINDOW");
        assertThat(stage.windowSize()).isEqualTo(16);
        assertThat(stage.visitedNodeCount()).isEqualTo(4);
        assertThat(stage.prunedNodeCount()).isEqualTo(2);

        assertThatThrownBy(() -> new SpatialStage(
                -1, "INITIAL_WINDOW", 16, null, null, null, null, 0, 0, 0
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new SpatialStage(
                0, null, 16, null, null, null, null, 0, 0, 0
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("DispatchTrace defensively copies collections and guarantees immutability")
    void dispatchTraceImmutability() {
        List<SpatialStage> stages = new ArrayList<>();
        stages.add(new SpatialStage(0, "STAGE_1", 16, null, null, null, null, 2, 1, 2));

        List<TraceCandidate> candidates = new ArrayList<>();
        candidates.add(new TraceCandidate("D1", 17.0, 78.0, 1.0, 0.1, 0.1, 0.0, 0.2, 1, CandidateOutcome.MATCHED, null));

        DispatchTrace trace = new DispatchTrace(
                "req-1",
                "GreedyMatcher (Spatial QuadTree)",
                17.3850,
                78.4867,
                Instant.now(),
                500_000L,
                stages,
                candidates,
                "D1",
                true,
                10,
                "Matched D1"
        );

        // Modifying original input list does not affect the immutable trace
        stages.add(new SpatialStage(1, "STAGE_2", 64, null, null, null, null, 4, 2, 4));
        assertThat(trace.stages()).hasSize(1);

        candidates.add(new TraceCandidate("D2", 17.1, 78.1, 2.0, 0.2, 0.2, 0.0, 0.4, 2, CandidateOutcome.OUTCOMPETED, "Outcompeted"));
        assertThat(trace.candidates()).hasSize(1);

        // Modifying returned list is rejected
        assertThatThrownBy(() -> trace.stages().add(new SpatialStage(2, "STAGE_3", 128, null, null, null, null, 1, 1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> trace.candidates().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("DispatchTraceConfig clamps out-of-range bounds to safe defaults")
    void configClamping() {
        DispatchTraceConfig maxConfig = new DispatchTraceConfig(500, 100, 10_000, Duration.ofSeconds(-10), true);

        assertThat(maxConfig.maxCandidatesPerTrace()).isEqualTo(100);   // clamped to max 100
        assertThat(maxConfig.maxStagesPerTrace()).isEqualTo(50);       // clamped to max 50
        assertThat(maxConfig.maxStoredTraces()).isEqualTo(5000);       // clamped to max 5000
        assertThat(maxConfig.traceTtl()).isEqualTo(Duration.ofMinutes(10)); // fallback to default 10m

        DispatchTraceConfig minConfig = new DispatchTraceConfig(-5, -2, -10, Duration.ZERO, true);
        assertThat(minConfig.maxCandidatesPerTrace()).isEqualTo(1);
        assertThat(minConfig.maxStagesPerTrace()).isEqualTo(1);
        assertThat(minConfig.maxStoredTraces()).isEqualTo(1);
        assertThat(minConfig.traceTtl()).isEqualTo(Duration.ofMinutes(10));
    }
}
