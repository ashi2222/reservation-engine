package com.ashish.reservation_engine.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadTestInfrastructureTest {

    @Test
    @DisplayName("BenchmarkConfig supports configurable parameters and validates input boundaries")
    void testConfigValidationAndCustomization() {
        BenchmarkConfig config = BenchmarkConfig.builder()
                .resourceId(42L)
                .totalRequests(50)
                .concurrencyLevel(5)
                .quantity(2)
                .userIdGenerator(index -> "custom-user-" + index)
                .idempotencyKeyGenerator(index -> "custom-key-" + index)
                .build();

        assertEquals(42L, config.getResourceId());
        assertEquals(50, config.getTotalRequests());
        assertEquals(5, config.getConcurrencyLevel());
        assertEquals(2, config.getQuantity());
        assertEquals("custom-user-7", config.getUserIdGenerator().apply(7));
        assertEquals("custom-key-7", config.getIdempotencyKeyGenerator().apply(7));

        // Validation bounds
        assertThrows(NullPointerException.class, () ->
                BenchmarkConfig.builder().build());

        assertThrows(IllegalArgumentException.class, () ->
                BenchmarkConfig.builder().resourceId(1L).totalRequests(0).build());

        assertThrows(IllegalArgumentException.class, () ->
                BenchmarkConfig.builder().resourceId(1L).concurrencyLevel(0).build());

        assertThrows(IllegalArgumentException.class, () ->
                BenchmarkConfig.builder().resourceId(1L).quantity(0).build());
    }

    @Test
    @DisplayName("LoadTestExecutor executes requests concurrently and records accurate counts and latencies")
    void testConcurrentExecutionAndMetricsCollection() throws InterruptedException {
        int totalRequests = 40;
        int concurrencyLevel = 8;

        BenchmarkConfig config = BenchmarkConfig.builder()
                .resourceId(101L)
                .totalRequests(totalRequests)
                .concurrencyLevel(concurrencyLevel)
                .quantity(1)
                .userIdGenerator(i -> "user-" + i)
                .idempotencyKeyGenerator(i -> "key-" + i)
                .build();

        AtomicInteger currentActive = new AtomicInteger(0);
        AtomicInteger maxObservedConcurrent = new AtomicInteger(0);

        // Simulated client that records concurrency and returns mixed outcomes
        ReservationClient mockClient = (index, request) -> {
            int active = currentActive.incrementAndGet();
            maxObservedConcurrent.updateAndGet(max -> Math.max(max, active));

            try {
                // Simulate small work window so concurrent threads overlap
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                currentActive.decrementAndGet();
            }

            long simulatedLatencyNano = 10_000_000L + (index * 100_000L); // 10ms + delta

            if (index < 25) {
                return RequestResult.success(index, request.getUserId(), request.getIdempotencyKey(), 201, simulatedLatencyNano, "Created");
            } else if (index < 35) {
                return RequestResult.rejected(index, request.getUserId(), request.getIdempotencyKey(), 409, simulatedLatencyNano, "Insufficient capacity");
            } else {
                return RequestResult.failed(index, request.getUserId(), request.getIdempotencyKey(), 500, simulatedLatencyNano, "Network timeout", null);
            }
        };

        LoadTestExecutor executor = new LoadTestExecutor(mockClient);
        BenchmarkResult result = executor.execute(config);

        // Verification of execution
        assertEquals(totalRequests, result.getTotalRequests());
        assertEquals(25, result.getSuccessfulRequests());
        assertEquals(10, result.getRejectedRequests());
        assertEquals(5, result.getFailedRequests());

        // Concurrency verification: more than 1 thread was running concurrently
        assertTrue(maxObservedConcurrent.get() > 1, "Requests must execute concurrently (max concurrent observed was: " + maxObservedConcurrent.get() + ")");

        // Latency and duration verification
        assertTrue(result.getTotalDurationNanos() > 0, "Total duration must be positive");
        assertTrue(result.getThroughput() > 0.0, "Throughput must be positive");
        assertEquals(totalRequests, result.getRequestResults().size());
        assertEquals(totalRequests, result.getLatenciesNanos().size());
        assertEquals(totalRequests, result.getLatenciesMs().size());

        // Percentile ordering verification: min <= p50 <= p95 <= p99 <= max
        assertTrue(result.getMinLatencyMs() <= result.getP50LatencyMs());
        assertTrue(result.getP50LatencyMs() <= result.getP95LatencyMs());
        assertTrue(result.getP95LatencyMs() <= result.getP99LatencyMs());
        assertTrue(result.getP99LatencyMs() <= result.getMaxLatencyMs());

        // Check report formatting
        String summary = result.getSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("Benchmark Summary:"));
        assertTrue(summary.contains("Total Requests:        40"));
        assertTrue(summary.contains("Successful Requests:   25"));
        assertTrue(summary.contains("Rejected Requests:     10"));
        assertTrue(summary.contains("Failed Requests:       5"));
    }

    @Test
    @DisplayName("BenchmarkResult accurately calculates percentiles on known latency sets")
    void testPercentileCalculations() {
        BenchmarkConfig config = BenchmarkConfig.builder()
                .resourceId(1L)
                .totalRequests(100)
                .concurrencyLevel(10)
                .build();

        List<RequestResult> results = new ArrayList<>();
        // Populate 100 requests with latencies 1ms to 100ms
        for (int i = 1; i <= 100; i++) {
            results.add(RequestResult.success(i, "u" + i, "k" + i, 200, i * 1_000_000L, "OK"));
        }

        BenchmarkResult result = new BenchmarkResult(
                config, 100, 100, 0, 0, 1_000_000_000L, results
        );

        assertEquals(1.0, result.getMinLatencyMs(), 0.01);
        assertEquals(100.0, result.getMaxLatencyMs(), 0.01);
        assertEquals(50.5, result.getMeanLatencyMs(), 0.01);

        // Nearest rank percentiles on 100 elements:
        // p50 -> index ceil(50/100 * 100) - 1 = 49 -> element 50.0 ms
        assertEquals(50.0, result.getP50LatencyMs(), 0.01);
        // p95 -> index ceil(95/100 * 100) - 1 = 94 -> element 95.0 ms
        assertEquals(95.0, result.getP95LatencyMs(), 0.01);
        // p99 -> index ceil(99/100 * 100) - 1 = 98 -> element 99.0 ms
        assertEquals(99.0, result.getP99LatencyMs(), 0.01);

        // 100 requests in 1 second = 100 req/sec throughput
        assertEquals(100.0, result.getThroughput(), 0.01);
    }
}

