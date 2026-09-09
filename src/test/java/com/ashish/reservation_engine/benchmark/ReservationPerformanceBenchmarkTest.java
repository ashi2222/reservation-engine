package com.ashish.reservation_engine.benchmark;

import com.ashish.reservation_engine.controller.ReservationController;
import com.ashish.reservation_engine.entity.Resource;
import com.ashish.reservation_engine.redis.RedisKeyBuilder;
import com.ashish.reservation_engine.redis.RedisResourceService;
import com.ashish.reservation_engine.repository.ReservationRepository;
import com.ashish.reservation_engine.repository.ResourceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 Part 3: Performance measurement benchmark suite for the Reservation Engine.
 *
 * Measures actual request latency using high-resolution monotonic timers (System.nanoTime),
 * calculates throughput (req/sec) from measured execution duration, computes latency percentiles
 * (p50, p95, p99, min, max, mean) from collected request samples, and outputs copy-ready documentation tables.
 *
 * All benchmark parameters (capacity, request count, concurrency level, quantity) are fully configurable.
 */
@SpringBootTest
class ReservationPerformanceBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(ReservationPerformanceBenchmarkTest.class);

    @Autowired
    private ReservationController reservationController;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private RedisResourceService redisResourceService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private final List<Long> createdResourceIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Long resourceId : createdResourceIds) {
            try {
                stringRedisTemplate.delete(RedisKeyBuilder.buildResourceCapacityKey(resourceId));
            } catch (Exception ignored) {
            }
        }
        createdResourceIds.clear();
    }

    /**
     * Core reusable benchmark execution harness supporting configurable capacities,
     * request counts, concurrency levels, and quantities.
     *
     * @param scenarioName     Descriptive label for the test scenario
     * @param resourceCapacity Initial inventory capacity
     * @param totalRequests    Total reservation requests to submit
     * @param concurrencyLevel Concurrency level (thread pool size)
     * @param quantity         Reservation quantity per attempt
     * @return PerformanceBenchmarkReport containing throughput, percentiles, and correctness results
     */
    public PerformanceBenchmarkReport runBenchmarkScenario(String scenarioName,
                                                          int resourceCapacity,
                                                          int totalRequests,
                                                          int concurrencyLevel,
                                                          int quantity) throws InterruptedException {
        // 1. Setup clean, controlled resource in PostgreSQL and Redis
        Resource resource = resourceRepository.save(
                new Resource(scenarioName + "-" + UUID.randomUUID(), resourceCapacity, resourceCapacity)
        );
        Long resourceId = resource.getId();
        createdResourceIds.add(resourceId);

        redisResourceService.initializeCapacity(resourceId, resourceCapacity);

        assertEquals(resourceCapacity, redisResourceService.getAvailableCapacity(resourceId));
        assertEquals(resourceCapacity, resourceRepository.findById(resourceId).orElseThrow().getAvailableCapacity());

        // 2. Build configuration with high-resolution tracking
        BenchmarkConfig config = BenchmarkConfig.builder()
                .resourceId(resourceId)
                .resourceCapacity(resourceCapacity)
                .totalRequests(totalRequests)
                .concurrencyLevel(concurrencyLevel)
                .quantity(quantity)
                .userIdGenerator(i -> "perf-user-" + i)
                .idempotencyKeyGenerator(i -> "perf-key-" + i + "-" + UUID.randomUUID())
                .build();

        // 3. Execute concurrently via real API flow: Controller -> Redis/Lua -> PostgreSQL
        ReservationClient client = new ControllerReservationClient(reservationController);
        LoadTestExecutor executor = new LoadTestExecutor(client);

        BenchmarkResult benchmarkResult = executor.execute(config);

        // 4. Query final system state
        Resource finalResource = resourceRepository.findById(resourceId).orElseThrow();
        int finalDbCapacity = finalResource.getAvailableCapacity();

        Integer finalRedisCapacity = redisResourceService.getAvailableCapacity(resourceId);
        assertNotNull(finalRedisCapacity, "Final Redis capacity must not be null");

        // 5. Generate comprehensive performance report
        PerformanceBenchmarkReport report = PerformanceBenchmarkReport.from(
                scenarioName,
                resourceCapacity,
                benchmarkResult,
                finalDbCapacity,
                finalRedisCapacity
        );

        // 6. Print copy-ready reports for project documentation
        log.info("\n{}", report.toConsoleReport());
        log.info("\n{}", report.toMarkdownTable());

        // 7. Verify hard correctness invariants (Phase 5 Part 2 requirements)
        assertEquals(totalRequests, report.totalRequests());
        assertTrue(report.successfulQuantity() <= resourceCapacity,
                "Successful quantity must never exceed resource capacity. Actual: " + report.successfulQuantity());
        assertEquals(0, report.oversoldQuantity(),
                "Oversold quantity must be 0. Actual: " + report.oversoldQuantity());
        assertEquals(0, report.unexpectedFailures(),
                "Unexpected failures must be 0. Actual: " + report.unexpectedFailures());

        // Final capacity consistency
        assertEquals(resourceCapacity - report.successfulQuantity(), finalDbCapacity,
                "PostgreSQL capacity must match initial minus successful quantity");
        assertEquals(resourceCapacity - report.successfulQuantity(), finalRedisCapacity.intValue(),
                "Redis capacity must match initial minus successful quantity");
        assertEquals(finalDbCapacity, finalRedisCapacity.intValue(),
                "PostgreSQL and Redis capacity states must be identical");

        return report;
    }

    @Test
    @DisplayName("Performance Benchmark: Configurable concurrent reservation load test with latency percentiles and throughput")
    void testConfigurableConcurrentReservationPerformance() throws InterruptedException {
        // Read parameters from System properties or use standard defaults:
        // capacity = 10, totalRequests = 1000, concurrencyLevel = 50, quantity = 1
        int capacity = Integer.getInteger("benchmark.capacity", 10);
        int totalRequests = Integer.getInteger("benchmark.requests", 1000);
        int concurrency = Integer.getInteger("benchmark.concurrency", 50);
        int quantity = Integer.getInteger("benchmark.quantity", 1);

        PerformanceBenchmarkReport report = runBenchmarkScenario(
                "High Contention Controllable Benchmark",
                capacity,
                totalRequests,
                concurrency,
                quantity
        );

        // Validate measured performance properties
        assertTrue(report.totalDurationMs() > 0.0, "Measured duration must be strictly positive");
        assertTrue(report.throughput() > 0.0, "Throughput must be strictly positive");
        assertTrue(report.minLatencyMs() > 0.0, "Min latency must be strictly positive");

        // Monotonic percentile ordering: min <= p50 <= p95 <= p99 <= max
        assertTrue(report.minLatencyMs() <= report.p50LatencyMs(), "Min latency must be <= p50");
        assertTrue(report.p50LatencyMs() <= report.p95LatencyMs(), "P50 latency must be <= p95");
        assertTrue(report.p95LatencyMs() <= report.p99LatencyMs(), "P95 latency must be <= p99");
        assertTrue(report.p99LatencyMs() <= report.maxLatencyMs(), "P99 latency must be <= max");
    }

    @Test
    @DisplayName("Performance Benchmark: Moderate Concurrency Scenario (500 requests, 100 capacity, 20 concurrency)")
    void testModerateConcurrencyPerformanceScenario() throws InterruptedException {
        PerformanceBenchmarkReport report = runBenchmarkScenario(
                "Moderate Concurrency Scenario",
                100,
                500,
                20,
                1
        );

        assertEquals(500, report.totalRequests());
        assertEquals(100, report.successfulRequests());
        assertEquals(400, report.rejectedRequests());
        assertEquals(0, report.oversoldQuantity());
        assertEquals(0, report.finalDbCapacity());
        assertEquals(0, report.finalRedisCapacity());
    }
}

