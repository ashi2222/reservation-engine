package com.ashish.reservation_engine.benchmark;

import com.ashish.reservation_engine.controller.ReservationController;
import com.ashish.reservation_engine.entity.Reservation;
import com.ashish.reservation_engine.entity.ReservationStatus;
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

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 Part 2: High-concurrency correctness test for the reservation engine.
 *
 * Primary Scenario:
 * - Resource initial capacity = 10
 * - Total concurrent reservation attempts = 1000
 * - Reservation quantity = 1 per request
 * - Each request uses a unique idempotency key
 * - Executes concurrently against the Spring Boot ReservationController (real reservation flow)
 *
 * Proves the core system invariant:
 * - successful reserved quantity <= 10
 * - oversold quantity == 0
 * - final PostgreSQL state is consistent
 * - final Redis capacity is consistent
 */
@SpringBootTest
class HighConcurrencyCorrectnessTest {

    private static final Logger log = LoggerFactory.getLogger(HighConcurrencyCorrectnessTest.class);

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

    private Long createdResourceId;

    @AfterEach
    void tearDown() {
        if (createdResourceId != null) {
            try {
                stringRedisTemplate.delete(RedisKeyBuilder.buildResourceCapacityKey(createdResourceId));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Data structure explicitly capturing the required Phase 5 Part 2 metrics report.
     */
    public record ConcurrencyCorrectnessReport(
            int totalRequests,
            int successfulRequests,
            int rejectedRequests,
            int unexpectedFailures,
            int successfulQuantity,
            int oversoldQuantity,
            int finalDbAvailableCapacity,
            int finalRedisAvailableCapacity
    ) {
        public String formatReport() {
            return String.format(
                    """
                    ============================================================
                    HIGH-CONCURRENCY CORRECTNESS REPORT (PHASE 5 PART 2)
                    ============================================================
                    Total Requests:                  %d
                    Successful Requests:            %d
                    Rejected Requests (HTTP 409):    %d
                    Unexpected Failures:            %d
                    Successful Reserved Quantity:    %d
                    Oversold Quantity:              %d
                    Final PostgreSQL Capacity:      %d
                    Final Redis Capacity:           %d
                    ============================================================
                    """,
                    totalRequests,
                    successfulRequests,
                    rejectedRequests,
                    unexpectedFailures,
                    successfulQuantity,
                    oversoldQuantity,
                    finalDbAvailableCapacity,
                    finalRedisAvailableCapacity
            );
        }
    }

    @Test
    @DisplayName("High Concurrency Correctness: 1000 concurrent requests competing for 10 capacity prevents overselling")
    void testHighConcurrencyCorrectnessPreventsOverselling() throws InterruptedException {
        int initialCapacity = 10;
        int totalRequests = 1000;
        int concurrencyLevel = 50;
        int requestQuantity = 1;

        // 1. Ensure clean, controlled resource state
        Resource resource = resourceRepository.save(new Resource("High-Contention-Hall-" + UUID.randomUUID(), initialCapacity, initialCapacity));
        createdResourceId = resource.getId();

        redisResourceService.initializeCapacity(createdResourceId, initialCapacity);

        // Verify initial state
        assertEquals(initialCapacity, redisResourceService.getAvailableCapacity(createdResourceId));
        assertEquals(initialCapacity, resourceRepository.findById(createdResourceId).orElseThrow().getAvailableCapacity());

        // 2. Build benchmark configuration using Phase 5 Part 1 infrastructure
        BenchmarkConfig config = BenchmarkConfig.builder()
                .resourceId(createdResourceId)
                .totalRequests(totalRequests)
                .concurrencyLevel(concurrencyLevel)
                .quantity(requestQuantity)
                .userIdGenerator(i -> "user-load-" + i)
                .idempotencyKeyGenerator(i -> "idemp-test-" + i + "-" + UUID.randomUUID())
                .build();

        // 3. Execute via real reservation flow: Client -> Spring Boot Controller -> Redis/Lua -> PostgreSQL
        ReservationClient client = new ControllerReservationClient(reservationController);
        LoadTestExecutor executor = new LoadTestExecutor(client);

        BenchmarkResult result = executor.execute(config);

        // 4. Calculate execution metrics
        int executedTotal = result.getTotalRequests();
        int successfulRequests = result.getSuccessfulRequests();
        int rejectedRequests = result.getRejectedRequests();
        int unexpectedFailures = result.getFailedRequests();

        int successfulQuantity = successfulRequests * requestQuantity;
        int oversoldQuantity = Math.max(0, successfulQuantity - initialCapacity);

        // 5. Query final state from PostgreSQL and Redis
        Resource finalResource = resourceRepository.findById(createdResourceId).orElseThrow();
        int finalDbAvailable = finalResource.getAvailableCapacity();

        Integer finalRedisAvailable = redisResourceService.getAvailableCapacity(createdResourceId);
        assertNotNull(finalRedisAvailable, "Final Redis capacity must not be null");

        List<Reservation> dbReservations = reservationRepository.findByResourceIdAndStatusIn(
                createdResourceId,
                Arrays.asList(ReservationStatus.values())
        );
        int totalDbReservedQuantity = dbReservations.stream()
                .filter(r -> r.getStatus() == ReservationStatus.HELD || r.getStatus() == ReservationStatus.CONFIRMED)
                .mapToInt(Reservation::getQuantity)
                .sum();

        // 6. Explicitly report metrics
        ConcurrencyCorrectnessReport report = new ConcurrencyCorrectnessReport(
                executedTotal,
                successfulRequests,
                rejectedRequests,
                unexpectedFailures,
                successfulQuantity,
                oversoldQuantity,
                finalDbAvailable,
                finalRedisAvailable
        );

        log.info("\n{}", report.formatReport());
        log.info("\n{}", result.getSummary());

        // 7. Verify hard correctness assertions
        // Invariant 1: Total requests accounted for
        assertEquals(totalRequests, executedTotal, "Total executed requests must equal configured count");
        assertEquals(totalRequests, successfulRequests + rejectedRequests + unexpectedFailures,
                "All requests must be categorized as successful, rejected, or failed");

        // Invariant 2: Hard correctness assertion - successful quantity <= 10 and oversold == 0
        assertTrue(successfulQuantity <= initialCapacity,
                "Successful quantity must never exceed total capacity. Actual: " + successfulQuantity);
        assertEquals(0, oversoldQuantity,
                "Oversold quantity must be exactly 0. Actual: " + oversoldQuantity);

        // Invariant 3: Expected capacity outcome on clean resource with 1000 quantity-1 attempts
        assertEquals(initialCapacity, successfulQuantity,
                "With 1000 competing requests for 10 capacity on a clean resource, exactly 10 requests should succeed");
        assertEquals(totalRequests - initialCapacity, rejectedRequests,
                "Remaining requests must be cleanly rejected due to capacity exhaustion");
        assertEquals(0, unexpectedFailures,
                "Unexpected failures/exceptions must be 0");

        // Invariant 4: PostgreSQL consistency
        assertEquals(initialCapacity - successfulQuantity, finalDbAvailable,
                "PostgreSQL available capacity must be decremented by exactly successful quantity");
        assertEquals(successfulQuantity, totalDbReservedQuantity,
                "Number of persisted HELD reservations in PostgreSQL must match successful client requests");
        assertEquals(successfulRequests, dbReservations.size(),
                "PostgreSQL reservation record count must match successful requests");

        // Invariant 5: Redis consistency
        assertEquals(initialCapacity - successfulQuantity, finalRedisAvailable.intValue(),
                "Redis available capacity must be decremented by exactly successful quantity");
        assertEquals(finalDbAvailable, finalRedisAvailable.intValue(),
                "Final PostgreSQL and Redis available capacities must be strictly consistent");
    }
}

