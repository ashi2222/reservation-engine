package com.ashish.reservation_engine.benchmark;

import com.ashish.reservation_engine.dto.CreateReservationRequest;

/**
 * Functional interface for executing a reservation request in the benchmark suite.
 * Enables decoupling the concurrency execution harness from the transport layer
 * (e.g. HTTP client, Spring MVC Test/MockMvc, or direct controller invocation).
 */
@FunctionalInterface
public interface ReservationClient {

    /**
     * Executes a single reservation request.
     *
     * @param requestIndex sequence index of the request
     * @param request      the reservation request payload
     * @return structured RequestResult containing status, latency, and response data
     */
    RequestResult execute(int requestIndex, CreateReservationRequest request);
}

