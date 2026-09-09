package com.ashish.reservation_engine.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Aggregated benchmark results for a concurrent reservation load test.
 * Structures the collected data to calculate throughput, p50, p95, p99 latencies,
 * and tracks successes, rejections, and failures.
 */
public class BenchmarkResult {

    private final BenchmarkConfig config;
    private final int totalRequests;
    private final int successfulRequests;
    private final int rejectedRequests;
    private final int failedRequests;
    private final long totalDurationNanos;
    private final List<RequestResult> requestResults;

    public BenchmarkResult(BenchmarkConfig config,
                           int totalRequests,
                           int successfulRequests,
                           int rejectedRequests,
                           int failedRequests,
                           long totalDurationNanos,
                           List<RequestResult> requestResults) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.totalRequests = totalRequests;
        this.successfulRequests = successfulRequests;
        this.rejectedRequests = rejectedRequests;
        this.failedRequests = failedRequests;
        this.totalDurationNanos = totalDurationNanos;
        this.requestResults = Collections.unmodifiableList(new ArrayList<>(requestResults));
    }

    public BenchmarkConfig getConfig() {
        return config;
    }

    public int getTotalRequests() {
        return totalRequests;
    }

    public int getSuccessfulRequests() {
        return successfulRequests;
    }

    public int getRejectedRequests() {
        return rejectedRequests;
    }

    public int getFailedRequests() {
        return failedRequests;
    }

    public long getTotalDurationNanos() {
        return totalDurationNanos;
    }

    public double getTotalDurationSeconds() {
        return totalDurationNanos / 1_000_000_000.0;
    }

    public double getTotalDurationMs() {
        return totalDurationNanos / 1_000_000.0;
    }

    public List<RequestResult> getRequestResults() {
        return requestResults;
    }

    /**
     * Calculates throughput in requests per second.
     */
    public double getThroughput() {
        double seconds = getTotalDurationSeconds();
        if (seconds <= 0.0) {
            return 0.0;
        }
        return totalRequests / seconds;
    }

    /**
     * Extracts individual request latencies in nanoseconds.
     */
    public List<Long> getLatenciesNanos() {
        return requestResults.stream()
                .map(RequestResult::getLatencyNanos)
                .collect(Collectors.toList());
    }

    /**
     * Extracts individual request latencies in milliseconds.
     */
    public List<Double> getLatenciesMs() {
        return requestResults.stream()
                .map(RequestResult::getLatencyMs)
                .collect(Collectors.toList());
    }

    /**
     * Calculates the specified percentile latency in milliseconds using the nearest-rank method.
     *
     * @param percentile percentile rank between 0.0 and 100.0 (e.g. 50.0, 95.0, 99.0)
     * @return latency in milliseconds
     */
    public double getPercentileLatencyMs(double percentile) {
        List<Double> latencies = getLatenciesMs();
        if (latencies.isEmpty()) {
            return 0.0;
        }
        if (percentile <= 0.0) {
            return Collections.min(latencies);
        }
        if (percentile >= 100.0) {
            return Collections.max(latencies);
        }

        List<Double> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);

        int index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    public double getP50LatencyMs() {
        return getPercentileLatencyMs(50.0);
    }

    public double getP95LatencyMs() {
        return getPercentileLatencyMs(95.0);
    }

    public double getP99LatencyMs() {
        return getPercentileLatencyMs(99.0);
    }

    public double getMinLatencyMs() {
        return requestResults.stream()
                .mapToDouble(RequestResult::getLatencyMs)
                .min()
                .orElse(0.0);
    }

    public double getMaxLatencyMs() {
        return requestResults.stream()
                .mapToDouble(RequestResult::getLatencyMs)
                .max()
                .orElse(0.0);
    }

    public double getMeanLatencyMs() {
        return requestResults.stream()
                .mapToDouble(RequestResult::getLatencyMs)
                .average()
                .orElse(0.0);
    }

    public String getSummary() {
        return String.format(
                "Benchmark Summary:%n" +
                "  Total Requests:        %d%n" +
                "  Concurrency Level:     %d%n" +
                "  Successful Requests:   %d%n" +
                "  Rejected Requests:     %d%n" +
                "  Failed Requests:       %d%n" +
                "  Total Duration:        %.2f ms (%.2f s)%n" +
                "  Throughput:            %.2f req/sec%n" +
                "  Latency Min:           %.2f ms%n" +
                "  Latency P50:           %.2f ms%n" +
                "  Latency P95:           %.2f ms%n" +
                "  Latency P99:           %.2f ms%n" +
                "  Latency Max:           %.2f ms%n" +
                "  Latency Mean:          %.2f ms",
                totalRequests,
                config.getConcurrencyLevel(),
                successfulRequests,
                rejectedRequests,
                failedRequests,
                getTotalDurationMs(),
                getTotalDurationSeconds(),
                getThroughput(),
                getMinLatencyMs(),
                getP50LatencyMs(),
                getP95LatencyMs(),
                getP99LatencyMs(),
                getMaxLatencyMs(),
                getMeanLatencyMs()
        );
    }

    public String getMarkdownTable() {
        return String.format(
                "| Metric | Value |%n" +
                "| :--- | :--- |%n" +
                "| Total Requests | %d |%n" +
                "| Concurrency Level | %d threads |%n" +
                "| Successful Requests | %d |%n" +
                "| Rejected Requests (HTTP 409) | %d |%n" +
                "| Unexpected Failures | %d |%n" +
                "| Total Duration | %.2f ms (%.3f s) |%n" +
                "| Throughput | %.2f req/sec |%n" +
                "| Latency Min | %.2f ms |%n" +
                "| Latency P50 (Median) | %.2f ms |%n" +
                "| Latency P95 | %.2f ms |%n" +
                "| Latency P99 | %.2f ms |%n" +
                "| Latency Max | %.2f ms |%n" +
                "| Latency Mean | %.2f ms |",
                totalRequests,
                config.getConcurrencyLevel(),
                successfulRequests,
                rejectedRequests,
                failedRequests,
                getTotalDurationMs(),
                getTotalDurationSeconds(),
                getThroughput(),
                getMinLatencyMs(),
                getP50LatencyMs(),
                getP95LatencyMs(),
                getP99LatencyMs(),
                getMaxLatencyMs(),
                getMeanLatencyMs()
        );
    }

    @Override
    public String toString() {
        return getSummary();
    }
}

