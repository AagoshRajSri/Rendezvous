package com.aagosh.dispatch.api;

/**
 * Standardized HTTP error response structure.
 */
public record ApiErrorResponse(
        int status,
        String error,
        String message
) {}
