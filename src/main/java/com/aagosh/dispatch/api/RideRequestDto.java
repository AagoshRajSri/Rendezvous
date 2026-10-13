package com.aagosh.dispatch.api;

/**
 * Request payload for submitting a new ride dispatch request.
 */
public record RideRequestDto(
        String requestId,
        String id,
        Double latitude,
        Double longitude
) {
    public String resolveId() {
        if (requestId != null && !requestId.isBlank()) {
            return requestId;
        }
        return id;
    }

    public void validate() {
        String effectiveId = resolveId();
        if (effectiveId == null || effectiveId.isBlank()) {
            throw new InvalidRequestException("requestId must not be null or blank");
        }
        if (latitude == null || !Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0) {
            throw new InvalidRequestException("latitude must be a finite number between -90.0 and 90.0");
        }
        if (longitude == null || !Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0) {
            throw new InvalidRequestException("longitude must be a finite number between -180.0 and 180.0");
        }
    }
}
