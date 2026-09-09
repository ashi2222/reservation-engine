package com.ashish.reservation_engine.benchmark;

/**
 * Encapsulates the complete performance measurement report for a reservation benchmark run.
 * Combines execution configuration, measured throughput, monotonic latency percentiles,
 * and database/Redis correctness verification.
 */
public record PerformanceBenchmarkReport(
        String scenarioName,
        int resourceCapacity,
        int totalRequests,
        int concurrencyLevel,
        int quantityPerRequest,
        int successfulRequests,
        int rejectedRequests,
        int unexpectedFailures,
        int successfulQuantity,
        int oversoldQuantity,
        int finalDbCapacity,
        int finalRedisCapacity,
        double totalDurationMs,
        double totalDurationSeconds,
        double throughput,
        double minLatencyMs,
        double p50LatencyMs,
        double p95LatencyMs,
        double p99LatencyMs,
        double maxLatencyMs,
        double meanLatencyMs
) {

    public static PerformanceBenchmarkReport from(String scenarioName,
                                                  int resourceCapacity,
                                                  BenchmarkResult result,
                                                  int finalDbCapacity,
                                                  int finalRedisCapacity) {
        BenchmarkConfig config = result.getConfig();
        int successfulQuantity = result.getSuccessfulRequests() * config.getQuantity();
        int oversoldQuantity = Math.max(0, successfulQuantity - resourceCapacity);

        return new PerformanceBenchmarkReport(
                scenarioName,
                resourceCapacity,
                result.getTotalRequests(),
                config.getConcurrencyLevel(),
                config.getQuantity(),
                result.getSuccessfulRequests(),
                result.getRejectedRequests(),
                result.getFailedRequests(),
                successfulQuantity,
                oversoldQuantity,
                finalDbCapacity,
                finalRedisCapacity,
                result.getTotalDurationMs(),
                result.getTotalDurationSeconds(),
                result.getThroughput(),
                result.getMinLatencyMs(),
                result.getP50LatencyMs(),
                result.getP95LatencyMs(),
                result.getP99LatencyMs(),
                result.getMaxLatencyMs(),
                result.getMeanLatencyMs()
        );
    }

    /**
     * Formats the report into a clean, human-readable ASCII console block.
     */
    public String toConsoleReport() {
        return String.format(
                """
                ================================================================================
                PERFORMANCE MEASUREMENT BENCHMARK REPORT: %s
                ================================================================================
                CONFIGURATION:
                  Resource Capacity:           %d
                  Total Reservation Attempts:  %d
                  Concurrency Level (Threads): %d
                  Quantity Per Attempt:        %d
                --------------------------------------------------------------------------------
                THROUGHPUT & DURATION (MEASURED MONOTONIC TIMER):
                  Total Duration:              %.2f ms (%.3f s)
                  Throughput:                  %.2f req/sec
                --------------------------------------------------------------------------------
                REQUEST OUTCOMES:
                  Total Executed:              %d
                  Successful Requests:         %d
                  Rejected Requests (HTTP 409):%d
                  Unexpected Failures:         %d
                --------------------------------------------------------------------------------
                CORRECTNESS & SAFETY INVARIANTS:
                  Successful Quantity:         %d (Max Allowed: %d)
                  Oversold Quantity:           %d (%s)
                  Final PostgreSQL Available:  %d
                  Final Redis Available:       %d
                --------------------------------------------------------------------------------
                LATENCY PERCENTILES (FROM INDIVIDUAL REQUEST SAMPLES):
                  Min Latency:                 %.2f ms
                  P50 (Median) Latency:        %.2f ms
                  P95 Latency:                 %.2f ms
                  P99 Latency:                 %.2f ms
                  Max Latency:                 %.2f ms
                  Mean Latency:                %.2f ms
                ================================================================================
                """,
                scenarioName,
                resourceCapacity,
                totalRequests,
                concurrencyLevel,
                quantityPerRequest,
                totalDurationMs,
                totalDurationSeconds,
                throughput,
                totalRequests,
                successfulRequests,
                rejectedRequests,
                unexpectedFailures,
                successfulQuantity,
                resourceCapacity,
                oversoldQuantity,
                oversoldQuantity == 0 ? "PASSED" : "FAILED",
                finalDbCapacity,
                finalRedisCapacity,
                minLatencyMs,
                p50LatencyMs,
                p95LatencyMs,
                p99LatencyMs,
                maxLatencyMs,
                meanLatencyMs
        );
    }

    /**
     * Formats the report into a clean Markdown table ready to be copied into project documentation.
     */
    public String toMarkdownTable() {
        return String.format(
                """
                ### Benchmark Results: %s

                | Benchmark Metric | Measured Value |
                | :--- | :--- |
                | **Resource Capacity** | %d |
                | **Total Requests** | %d |
                | **Concurrency Level** | %d threads |
                | **Quantity Per Request** | %d |
                | **Successful Requests** | %d |
                | **Rejected Requests (HTTP 409)** | %d |
                | **Unexpected Failures** | %d |
                | **Successful Quantity** | %d |
                | **Oversold Quantity** | %d |
                | **Final PostgreSQL Capacity** | %d |
                | **Final Redis Capacity** | %d |
                | **Total Duration** | %.2f ms (%.3f s) |
                | **Throughput** | **%.2f req/sec** |
                | **Min Latency** | %.2f ms |
                | **P50 (Median) Latency** | **%.2f ms** |
                | **P95 Latency** | **%.2f ms** |
                | **P99 Latency** | **%.2f ms** |
                | **Max Latency** | %.2f ms |
                | **Mean Latency** | %.2f ms |
                """,
                scenarioName,
                resourceCapacity,
                totalRequests,
                concurrencyLevel,
                quantityPerRequest,
                successfulRequests,
                rejectedRequests,
                unexpectedFailures,
                successfulQuantity,
                oversoldQuantity,
                finalDbCapacity,
                finalRedisCapacity,
                totalDurationMs,
                totalDurationSeconds,
                throughput,
                minLatencyMs,
                p50LatencyMs,
                p95LatencyMs,
                p99LatencyMs,
                maxLatencyMs,
                meanLatencyMs
        );
    }
}

