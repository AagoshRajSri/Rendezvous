package com.aagosh.dispatch.api;

import com.aagosh.dispatch.messaging.AdaptiveWindowController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * REST controller exposing read-only Kafka batch consumption and arrival-rate telemetry.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/telemetry/batches} (and {@code GET /telemetry/batches}): returns the current
 *       immutable {@link BatchTelemetryResponse} captured by {@link AdaptiveWindowController}.</li>
 * </ul>
 *
 * <h2>Design Safeguards</h2>
 * <ul>
 *   <li><strong>Read-only:</strong> Exposes telemetry metrics without offering reset or mutation endpoints,
 *       enforcing measurement validity via point-in-time deltas rather than disruptive resets.</li>
 *   <li><strong>No sensitive data:</strong> Contains strictly aggregate performance counters and Little's Law
 *       estimates; no driver PII, customer locations, or database credentials.</li>
 *   <li><strong>Decoupled from dispatch path:</strong> Completely independent of the request submission
 *       and matching pipelines.</li>
 * </ul>
 */
@RestController
@RequestMapping({"/api/telemetry", "/telemetry"})
public class TelemetryController {

    private final AdaptiveWindowController windowController;

    @Autowired
    public TelemetryController(AdaptiveWindowController windowController) {
        this.windowController = Objects.requireNonNull(windowController, "windowController");
    }

    /**
     * Returns the current point-in-time snapshot of batch consumption and arrival-rate metrics.
     *
     * @return 200 OK with {@link BatchTelemetryResponse}
     */
    @GetMapping("/batches")
    public ResponseEntity<BatchTelemetryResponse> getBatchTelemetry() {
        var snapshot = windowController.getTelemetrySnapshot();
        return ResponseEntity.ok(BatchTelemetryResponse.from(snapshot));
    }
}
