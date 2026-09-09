package com.ashish.reservation_engine.benchmark;

/**
 * Represents the outcome of an individual reservation request in a benchmark.
 */
public enum RequestStatus {
    /**
     * Reservation successfully admitted and confirmed/held.
     */
    SUCCESS,

    /**
     * Reservation rejected due to business constraints (e.g., insufficient inventory/capacity or 409 Conflict).
     */
    REJECTED,

    /**
     * Request failed due to an unexpected system error, exception, or timeout.
     */
    FAILED
}

