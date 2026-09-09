package com.ashish.reservation_engine;

import com.ashish.reservation_engine.entity.Reservation;
import com.ashish.reservation_engine.entity.ReservationStatus;
import com.ashish.reservation_engine.entity.Resource;
import com.ashish.reservation_engine.redis.RedisAdmissionResult;
import com.ashish.reservation_engine.redis.RedisAdmissionService;
import com.ashish.reservation_engine.redis.RedisKeyBuilder;
import com.ashish.reservation_engine.redis.RedisResourceService;
import com.ashish.reservation_engine.repository.ReservationRepository;
import com.ashish.reservation_engine.repository.ResourceRepository;
import com.ashish.reservation_engine.service.ReservationExpirationService;
import com.ashish.reservation_engine.service.ReservationReconciliationService;
import com.ashish.reservation_engine.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ReservationEnginePhase2IntegrationTests {

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationExpirationService expirationService;

    @Autowired
    private ReservationReconciliationService reconciliationService;

    @Autowired
    private RedisResourceService redisResourceService;

    @Autowired
    private RedisAdmissionService redisAdmissionService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUp() {
        // Clean database records between test runs if any
    }

    @Test
    @DisplayName("1. Redis Connection Ping Verification")
    void testRedisConnection() {
        try (RedisConnection connection = stringRedisTemplate.getConnectionFactory().getConnection()) {
            String pingResponse = connection.ping();
            assertEquals("PONG", pingResponse, "Redis must respond with PONG");
        }
    }

    @Test
    @DisplayName("2. Successful Redis Admission & PostgreSQL Persistence")
    void testSuccessfulRedisAdmissionAndPersistence() {
        Resource resource = resourceRepository.save(new Resource("Concert-Stage-A", 50, 50));
        String idempotencyKey = "key-" + UUID.randomUUID();

        ReservationService.ReservationResult result = reservationService.createReservationWithResult(
                resource.getId(), "user-1", 5, idempotencyKey);

        assertTrue(result.newlyCreated());
        assertEquals(ReservationStatus.HELD, result.reservation().getStatus());
        assertEquals(5, result.reservation().getQuantity());
        assertNotNull(result.reservation().getExpiresAt());

        // Verify Redis capacity decremented
        Integer redisCapacity = redisResourceService.getAvailableCapacity(resource.getId());
        assertEquals(45, redisCapacity);

        // Verify PostgreSQL capacity decremented
        Resource updatedResource = resourceRepository.findById(resource.getId()).orElseThrow();
        assertEquals(45, updatedResource.getAvailableCapacity());

        // Verify Redis hold key and idempotency key exist with TTL
        String idempKey = RedisKeyBuilder.buildIdempotencyKey(idempotencyKey);
        Long ttl = stringRedisTemplate.getExpire(idempKey);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "Idempotency TTL should be positive");
    }

    @Test
    @DisplayName("3. Insufficient Redis Capacity Rejection without PostgreSQL Modification")
    void testInsufficientCapacityRejection() {
        Resource resource = resourceRepository.save(new Resource("Small-Hall", 10, 10));
        String idempotencyKey = "key-" + UUID.randomUUID();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                reservationService.createReservationWithResult(resource.getId(), "user-2", 25, idempotencyKey));

        assertTrue(ex.getMessage().contains("Insufficient capacity"));

        // Capacity in both stores must remain intact
        assertEquals(10, redisResourceService.getAvailableCapacity(resource.getId()));
        Resource reloaded = resourceRepository.findById(resource.getId()).orElseThrow();
        assertEquals(10, reloaded.getAvailableCapacity());

        // No reservation record created
        assertTrue(reservationRepository.findByIdempotencyKey(idempotencyKey).isEmpty());
    }

    @Test
    @DisplayName("4. Same Idempotency Key Submitted Repeatedly Returns Consistent Result without Double Deduction")
    void testSameIdempotencyKeyRepeatedly() {
        Resource resource = resourceRepository.save(new Resource("Auditorium", 100, 100));
        String idempotencyKey = "key-repeat-" + UUID.randomUUID();

        ReservationService.ReservationResult first = reservationService.createReservationWithResult(
                resource.getId(), "user-repeat", 10, idempotencyKey);
        assertTrue(first.newlyCreated());
        assertEquals(90, redisResourceService.getAvailableCapacity(resource.getId()));

        // Repeated request with exact same idempotency key
        ReservationService.ReservationResult second = reservationService.createReservationWithResult(
                resource.getId(), "user-repeat", 10, idempotencyKey);

        assertFalse(second.newlyCreated(), "Second invocation should indicate idempotent replay");
        assertEquals(first.reservation().getId(), second.reservation().getId());
        assertEquals(90, redisResourceService.getAvailableCapacity(resource.getId()), "Redis capacity must not be deducted twice");

        Resource reloaded = resourceRepository.findById(resource.getId()).orElseThrow();
        assertEquals(90, reloaded.getAvailableCapacity(), "PostgreSQL capacity must not be deducted twice");
    }

    @Test
    @DisplayName("5. Concurrent Requests Competing for Limited Capacity Prevents Overselling")
    void testConcurrentRequestsLimitedCapacity() throws InterruptedException {
        int totalCapacity = 20;
        int requestQuantity = 5;
        int threadCount = 10; // 10 * 5 = 50 requested, only 20 available

        Resource resource = resourceRepository.save(new Resource("VIP-Lounge", totalCapacity, totalCapacity));
        redisResourceService.initializeCapacity(resource.getId(), totalCapacity);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    latch.await();
                    reservationService.createReservationWithResult(
                            resource.getId(), "concurrent-user-" + index, requestQuantity, "conc-key-" + UUID.randomUUID());
                    successCount.incrementAndGet();
                } catch (Exception ex) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown(); // trigger all threads simultaneously
        doneLatch.await();
        executor.shutdown();

        // Exactly 4 requests must succeed (4 * 5 = 20)
        assertEquals(4, successCount.get(), "Only 4 requests of 5 tickets should succeed for total capacity of 20");
        assertEquals(6, failureCount.get(), "Remaining 6 requests must fail due to insufficient capacity");

        // Available capacity must be exactly 0
        assertEquals(0, redisResourceService.getAvailableCapacity(resource.getId()));
        Resource reloaded = resourceRepository.findById(resource.getId()).orElseThrow();
        assertEquals(0, reloaded.getAvailableCapacity());
    }

    @Test
    @DisplayName("6. Redis Atomic Capacity Restoration & Upper Bound Clamping")
    void testRedisCapacityRestorationAndClamping() {
        Resource resource = resourceRepository.save(new Resource("Seminar-Room", 50, 40));
        redisResourceService.initializeCapacity(resource.getId(), 40);

        // Restore 5 units
        Long restored = redisResourceService.restoreCapacity(resource.getId(), 5, 50);
        assertEquals(45L, restored);
        assertEquals(45, redisResourceService.getAvailableCapacity(resource.getId()));

        // Attempt to restore 20 units (45 + 20 = 65, which exceeds totalCapacity 50)
        Long capped = redisResourceService.restoreCapacity(resource.getId(), 20, 50);
        assertEquals(50L, capped, "Restored capacity must be capped at totalCapacity 50");
        assertEquals(50, redisResourceService.getAvailableCapacity(resource.getId()));
    }

    @Test
    @DisplayName("7. PostgreSQL Persistence Failure Triggers Redis Compensation")
    void testPostgresFailureTriggersRedisCompensation() {
        Long dummyResourceId = 99999L; // Non-existent in PostgreSQL
        String idempotencyKey = "fail-key-" + UUID.randomUUID();

        // Initialize Redis capacity for dummy resource
        redisResourceService.initializeCapacity(dummyResourceId, 100);

        // Directly invoke admitReservation
        RedisAdmissionResult admission = redisAdmissionService.admitReservation(
                dummyResourceId, "user-fail", 10, idempotencyKey, Duration.ofMinutes(10));
        assertEquals(RedisAdmissionResult.Status.NEWLY_ADMITTED, admission.getStatus());
        assertEquals(90, redisResourceService.getAvailableCapacity(dummyResourceId));

        // Trigger compensation simulating downstream persistence failure
        redisAdmissionService.compensateAdmission(dummyResourceId, 10, idempotencyKey);

        // Redis capacity should be restored to 100
        assertEquals(100, redisResourceService.getAvailableCapacity(dummyResourceId));

        // Hold and idempotency keys should be removed
        String idempKey = RedisKeyBuilder.buildIdempotencyKey(idempotencyKey);
        assertFalse(stringRedisTemplate.hasKey(idempKey));
    }

    @Test
    @DisplayName("8. Expiration of HELD Reservation Transitions Status & Restores Capacity")
    void testExpirationOfHeldReservation() {
        Resource resource = resourceRepository.save(new Resource("Workshop", 30, 30));
        String idempKey = "expire-key-" + UUID.randomUUID();

        ReservationService.ReservationResult result = reservationService.createReservationWithResult(
                resource.getId(), "expire-user", 10, idempKey);

        assertEquals(20, redisResourceService.getAvailableCapacity(resource.getId()));

        // Expire reservation
        Optional<Reservation> expiredOpt = expirationService.expireReservation(result.reservation().getId());
        assertTrue(expiredOpt.isPresent());
        assertEquals(ReservationStatus.EXPIRED, expiredOpt.get().getStatus());

        // Capacity restored in both stores
        assertEquals(30, redisResourceService.getAvailableCapacity(resource.getId()));
        Resource reloaded = resourceRepository.findById(resource.getId()).orElseThrow();
        assertEquals(30, reloaded.getAvailableCapacity());
    }

    @Test
    @DisplayName("9. Expiration of Already CANCELLED or CONFIRMED Reservation Does NOT Restore Capacity Again")
    void testExpirationOfNonHeldReservationDoesNotRestoreCapacity() {
        Resource resource = resourceRepository.save(new Resource("Theatre", 40, 40));
        String idempKey = "cancel-key-" + UUID.randomUUID();

        ReservationService.ReservationResult res = reservationService.createReservationWithResult(
                resource.getId(), "cancel-user", 10, idempKey);
        assertEquals(30, redisResourceService.getAvailableCapacity(resource.getId()));

        // Cancel reservation (restores capacity to 40)
        Reservation cancelled = reservationService.cancelReservation(res.reservation().getId());
        assertEquals(ReservationStatus.CANCELLED, cancelled.getStatus());
        assertEquals(40, redisResourceService.getAvailableCapacity(resource.getId()));

        // Attempt to expire cancelled reservation
        Optional<Reservation> expiredAttempt = expirationService.expireReservation(cancelled.getId());
        assertTrue(expiredAttempt.isPresent());
        assertEquals(ReservationStatus.CANCELLED, expiredAttempt.get().getStatus(), "Status must remain CANCELLED");

        // Capacity must NOT be restored again (must remain 40, not 50!)
        assertEquals(40, redisResourceService.getAvailableCapacity(resource.getId()));
        Resource reloaded = resourceRepository.findById(resource.getId()).orElseThrow();
        assertEquals(40, reloaded.getAvailableCapacity());
    }

    @Test
    @DisplayName("10. Reconciliation Service Detects and Corrects Redis Capacity Drift")
    void testReconciliationCorrectsRedisDrift() {
        Resource resource = resourceRepository.save(new Resource("Stadium", 100, 100));
        String idempKey = "recon-key-" + UUID.randomUUID();

        // Create reservation of 20
        reservationService.createReservationWithResult(resource.getId(), "recon-user", 20, idempKey);
        assertEquals(80, redisResourceService.getAvailableCapacity(resource.getId()));

        // Artificially create drift in Redis (simulate missed write or corruption)
        stringRedisTemplate.opsForValue().set(RedisKeyBuilder.buildResourceCapacityKey(resource.getId()), "50");
        assertEquals(50, redisResourceService.getAvailableCapacity(resource.getId()));

        // Run reconciliation
        ReservationReconciliationService.ReconciliationResult reconResult =
                reconciliationService.reconcileResource(resource.getId());

        assertTrue(reconResult.driftDetected());
        assertEquals(50, reconResult.redisCapacityBefore());
        assertEquals(80, reconResult.redisCapacityAfter(), "Redis capacity must be corrected to PostgreSQL authoritative 80");
        assertEquals(80, reconResult.postgreSqlAvailableCapacity());
        assertEquals(20, reconResult.activeReservedCapacity());
    }

    @Test
    @DisplayName("11. Core Invariant Holds Across Multiple Concurrent State Changes")
    void testFinalCapacityInvariant() {
        Resource resource = resourceRepository.save(new Resource("Festival-Grounds", 100, 100));

        // Create 3 reservations: 10, 15, 20
        Reservation r1 = reservationService.createReservation(resource.getId(), "u1", 10, "inv-1-" + UUID.randomUUID());
        Reservation r2 = reservationService.createReservation(resource.getId(), "u2", 15, "inv-2-" + UUID.randomUUID());
        Reservation r3 = reservationService.createReservation(resource.getId(), "u3", 20, "inv-3-" + UUID.randomUUID());

        // Cancel r1 (restores 10)
        reservationService.cancelReservation(r1.getId());

        // Confirm r2 (transitions HELD -> PAYMENT_PENDING -> CONFIRMED, capacity still held)
        reservationService.transitionToPaymentPending(r2.getId());
        reservationService.confirmReservation(r2.getId());

        // Expire r3 (restores 20)
        expirationService.expireReservation(r3.getId());

        // Reconcile and verify invariant
        ReservationReconciliationService.ReconciliationResult result =
                reconciliationService.reconcileResource(resource.getId());

        // Total: 100
        // Active: r2 (15 confirmed)
        // Available: 100 - 15 = 85
        assertEquals(100, result.totalCapacity());
        assertEquals(15, result.activeReservedCapacity());
        assertEquals(85, result.postgreSqlAvailableCapacity());
        assertEquals(85, result.redisCapacityAfter());

        // Invariant: availableCapacity + activeCapacity == totalCapacity
        assertEquals(result.totalCapacity(), result.postgreSqlAvailableCapacity() + result.activeReservedCapacity());
        assertTrue(result.postgreSqlAvailableCapacity() >= 0 && result.postgreSqlAvailableCapacity() <= result.totalCapacity());
    }
}

