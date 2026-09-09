package com.ashish.reservation_engine.benchmark;

import com.ashish.reservation_engine.entity.Reservation;
import com.ashish.reservation_engine.entity.ReservationStatus;
import com.ashish.reservation_engine.entity.Resource;
import com.ashish.reservation_engine.lock.DistributedLockService;
import com.ashish.reservation_engine.payment.PaymentProcessingException;
import com.ashish.reservation_engine.payment.PaymentProcessorService;
import com.ashish.reservation_engine.payment.PaymentSimulationService;
import com.ashish.reservation_engine.redis.RedisKeyBuilder;
import com.ashish.reservation_engine.redis.RedisResourceService;
import com.ashish.reservation_engine.repository.ReservationRepository;
import com.ashish.reservation_engine.repository.ResourceRepository;
import com.ashish.reservation_engine.service.ReservationExpirationService;
import com.ashish.reservation_engine.service.ReservationReconciliationService;
import com.ashish.reservation_engine.service.ReservationReconciliationService.ReconciliationResult;
import com.ashish.reservation_engine.service.ReservationService;
import com.ashish.reservation_engine.worker.ExpiryWorker;
import com.ashish.reservation_engine.worker.ReconciliationWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 Part 4: Failure and Convergence Verification Suite.
 *
 * Verifies system correctness under failure modes, lifecycle edge cases,
 * worker recovery interactions, and state machine convergence.
 */
@SpringBootTest
class FailureAndConvergenceVerificationTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationExpirationService expirationService;

    @Autowired
    private ReservationReconciliationService reconciliationService;

    @Autowired
    private PaymentProcessorService paymentProcessorService;

    @Autowired
    private PaymentSimulationService paymentSimulationService;

    @Autowired
    private ExpiryWorker expiryWorker;

    @Autowired
    private ReconciliationWorker reconciliationWorker;

    @Autowired
    private DistributedLockService distributedLockService;

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
        paymentSimulationService.clearOverrides();
        for (Long resourceId : createdResourceIds) {
            try {
                stringRedisTemplate.delete(RedisKeyBuilder.buildResourceCapacityKey(resourceId));
            } catch (Exception ignored) {
            }
        }
        createdResourceIds.clear();
    }

    private Resource createControlledResource(String prefix, int capacity) {
        Resource resource = resourceRepository.save(
                new Resource(prefix + "-" + UUID.randomUUID(), capacity, capacity)
        );
        Long resourceId = resource.getId();
        createdResourceIds.add(resourceId);
        redisResourceService.initializeCapacity(resourceId, capacity);
        return resource;
    }

    @Test
    @DisplayName("Scenario 1: Reservation creation followed by expiration restores capacity in PostgreSQL and Redis")
    void testReservationCreationFollowedByExpiration() {
        int initialCapacity = 10;
        int reservedQuantity = 3;
        Resource resource = createControlledResource("Scenario1-Expiry", initialCapacity);

        Reservation reservation = reservationService.createReservation(
                resource.getId(), "user-exp-1", reservedQuantity, "key-exp-" + UUID.randomUUID()
        );

        // Verify pre-expiration state
        assertEquals(ReservationStatus.HELD, reservation.getStatus());
        assertEquals(initialCapacity - reservedQuantity, redisResourceService.getAvailableCapacity(resource.getId()));
        assertEquals(initialCapacity - reservedQuantity, resourceRepository.findById(resource.getId()).orElseThrow().getAvailableCapacity());

        // Fast-forward expiration into the past
        reservation.setExpiresAt(Instant.now().minusSeconds(60));
        reservationRepository.save(reservation);

        // Execute expiration
        Optional<Reservation> expiredOpt = expirationService.expireReservation(reservation.getId());
        assertTrue(expiredOpt.isPresent());
        assertEquals(ReservationStatus.EXPIRED, expiredOpt.get().getStatus());

        // Verify capacity is restored in both PostgreSQL and Redis
        assertEquals(initialCapacity, resourceRepository.findById(resource.getId()).orElseThrow().getAvailableCapacity(),
                "PostgreSQL capacity must be restored to initial capacity");
        assertEquals(initialCapacity, redisResourceService.getAvailableCapacity(resource.getId()),
                "Redis capacity must be restored to initial capacity");
    }

    @Test
    @DisplayName("Scenario 2: Reservation creation followed by cancellation restores capacity in PostgreSQL and Redis")
    void testReservationCreationFollowedByCancellation() {
        int initialCapacity = 15;
        int reservedQuantity = 5;
        Resource resource = createControlledResource("Scenario2-Cancel", initialCapacity);

        Reservation reservation = reservationService.createReservation(
                resource.getId(), "user-cancel-1", reservedQuantity, "key-cancel-" + UUID.randomUUID()
        );

        assertEquals(ReservationStatus.HELD, reservation.getStatus());
        assertEquals(10, redisResourceService.getAvailableCapacity(resource.getId()));
        assertEquals(10, resourceRepository.findById(resource.getId()).orElseThrow().getAvailableCapacity());

        // Cancel the reservation
        Reservation cancelled = reservationService.cancelReservation(reservation.getId());
        assertEquals(ReservationStatus.CANCELLED, cancelled.getStatus());

        // Verify restoration
        assertEquals(initialCapacity, resourceRepository.findById(resource.getId()).orElseThrow().getAvailableCapacity(),
                "PostgreSQL capacity must be restored on cancellation");
        assertEquals(initialCapacity, redisResourceService.getAvailableCapacity(resource.getId()),
                "Redis capacity must be restored on cancellation");
    }

    @Test
    @DisplayName("Scenario 3: Redis hold key expiration triggers reliable application-level state transition and capacity restoration")
    void testRedisHoldExpirationTriggersApplicationLevelTransition() {
        int initialCapacity = 8;
        int reservedQuantity = 2;
        Resource resource = createControlledResource("Scenario3-HoldExpiry", initialCapacity);
        String idempotencyKey = "key-hold-exp-" + UUID.randomUUID();

        Reservation reservation = reservationService.createReservation(
                resource.getId(), "user-hold-3", reservedQuantity, idempotencyKey
        );

        assertEquals(6, redisResourceService.getAvailableCapacity(resource.getId()));

        // Simulate TTL expiration of Redis hold and idempotency keys (keys disappear in Redis)
        stringRedisTemplate.delete(RedisKeyBuilder.buildReservationHoldKey(idempotencyKey));
        stringRedisTemplate.delete(RedisKeyBuilder.buildIdempotencyKey(idempotencyKey));

        // Advance expiration cutoff in PostgreSQL
        reservation.setExpiresAt(Instant.now().minusSeconds(10));
        reservationRepository.save(reservation);

        // Run batch expiration worker
        int expiredCount = expirationService.expireExpiredHoldsBatch(Instant.now(), 10);
        assertTrue(expiredCount >= 1, "At least one reservation hold should be expired");
        // Run expiry worker to drain and process expired holds across batches
        int expiredCount = expiryWorker.processExpiredReservations();
        assertTrue(expiredCount >= 1, "At least one reservation hold should be expired by worker");

        Reservation reloaded = reservationRepository.findById(reservation.getId()).orElseThrow();
        assertEquals(ReservationStatus.EXPIRED, reloaded.getStatus());

        // Capacity restored
        assertEquals(initialCapacity, resourceRepository.findById(resource.getId()).orElseThrow().getAvailableCapacity());
        assertEquals(initialCapacity, redisResourceService.getAvailableCapacity(resource.getId()));
    }

    @Test
    @DisplayName("Scenario 4: Deliberate Redis/PostgreSQL capacity drift is detected and converged by reconciliation")
    void testCapacityDriftDetectionAndReconciliation() {
        int totalCapacity = 20;
        Resource resource = createControlledResource("Scenario4-Drift", totalCapacity);

        // Reserve 5 units -> True available capacity is 15
        reservationService.createReservation(
                resource.getId(), "user-drift-1", 5, "key-drift-" + UUID.randomUUID()
        );
        assertEquals(15, redisResourceService.getAvailableCapacity(resource.getId()));
        assertEquals(15, resourceRepository.findById(resource.getId()).orElseThrow().getAvailableCapacity());

        // Deliberately corrupt Redis capacity to 99 (severe upward drift)
        stringRedisTemplate.opsForValue().set(
                RedisKeyBuilder.buildResourceCapacityKey(resource.getId()), "99"
        );
        assertEquals(99, redisResourceService.getAvailableCapacity(resource.getId()));

        // Execute reconciliation service
        ReconciliationResult result = reconciliationService.reconcileResource(resource.getId());

        assertTrue(result.driftDetected(), "Reconciliation must detect capacity drift");
        assertEquals(84, result.driftAmount(), "Drift amount should be 99 - 15 = 84");
        assertEquals(15, result.expectedAvailableCapacity());
        assertEquals(15, result.redisCapacityAfter(), "Redis capacity must be corrected to 15");

        // Verify Redis now matches PostgreSQL source of truth
        assertEquals(15, redisResourceService.getAvailableCapacity(resource.getId()));
        assertEquals(15, resourceRepository.findById(resource.getId()).orElseThrow().getAvailableCapacity());
    }

    @Test
    @DisplayName("Scenario 5: Duplicate and repeated lifecycle calls do not restore capacity more than once")
    void testDuplicateLifecycleProcessingDoesNotRestoreCapacityTwice() {
        int initialCapacity = 10;
        int reservedQuantity = 4;
        Resource resource = createControlledResource("Scenario5-DoubleRestore", initialCapacity);

        Reservation reservation = reservationService.createReservation(
                resource.getId(), "user-repeat-5", reservedQuantity, "key-repeat-" + UUID.randomUUID()
        );
        assertEquals(6, redisResourceService.getAvailableCapacity(resource.getId()));

        // 1. First expiration: restores 4 units -> capacity becomes 10
        reservation.setExpiresAt(Instant.now().minusSeconds(30));
        reservationRepository.save(reservation);

        expirationService.expireReservation(reservation.getId());
        assertEquals(10, redisResourceService.getAvailableCapacity(resource.getId()));
        assertEquals(10, resourceRepository.findById(resource.getId()).orElseThrow().getAvailableCapacity());

        // 2. Second (duplicate) expiration invocation on already EXPIRED reservation
        expirationService.expireReservation(reservation.getId());

        // Capacity MUST remain 10, not 14!
        assertEquals(10, resourceRepository.findById(resource.getId()).orElseThrow().getAvailableCapacity(),
                "PostgreSQL capacity must not be restored twice for already EXPIRED reservation");
        assertEquals(10, redisResourceService.getAvailableCapacity(resource.getId()),
                "Redis capacity must not be restored twice for already EXPIRED reservation");

        // 3. Attempting cancellation on an already EXPIRED reservation must fail and not restore
        assertThrows(IllegalStateException.class, () ->
                reservationService.cancelReservation(reservation.getId()));

        assertEquals(10, resourceRepository.findById(resource.getId()).orElseThrow().getAvailableCapacity());
        assertEquals(10, redisResourceService.getAvailableCapacity(resource.getId()));
    }

    @Test
    @DisplayName("Scenario 6: Final Redis capacity converges to PostgreSQL-derived active reservation source of truth")
    void testConvergenceToPostgreSqlSourceOfTruth() {
        int totalCapacity = 50;
        Resource resource = createControlledResource("Scenario6-MultiState", totalCapacity);

        // Create reservations in various states:
        // 1. HELD: 5 units
        Reservation held = reservationService.createReservation(
                resource.getId(), "user-state-1", 5, "key-st-1-" + UUID.randomUUID());

        // 2. CONFIRMED: 10 units (via payment pending -> confirmed)
        Reservation conf = reservationService.createReservation(
                resource.getId(), "user-state-2", 10, "key-st-2-" + UUID.randomUUID());
        reservationService.transitionToPaymentPending(conf.getId());
        reservationService.confirmReservation(conf.getId());

        // 3. PAYMENT_PENDING: 5 units
        Reservation pend = reservationService.createReservation(
                resource.getId(), "user-state-3", 5, "key-st-3-" + UUID.randomUUID());
        reservationService.transitionToPaymentPending(pend.getId());

        // 4. CANCELLED: 8 units (capacity already restored once)
        Reservation canc = reservationService.createReservation(
                resource.getId(), "user-state-4", 8, "key-st-4-" + UUID.randomUUID());
        reservationService.cancelReservation(canc.getId());

        // 5. EXPIRED: 7 units (capacity already restored once)
        Reservation exp = reservationService.createReservation(
                resource.getId(), "user-state-5", 7, "key-st-5-" + UUID.randomUUID());
        exp.setExpiresAt(Instant.now().minusSeconds(10));
        reservationRepository.save(exp);
        expirationService.expireReservation(exp.getId());

        // Active reservations: HELD (5) + CONFIRMED (10) + PAYMENT_PENDING (5) = 20 active units.
        // Expected available capacity = 50 - 20 = 30.
        int expectedAvailable = 30;

        assertEquals(expectedAvailable, resourceRepository.findById(resource.getId()).orElseThrow().getAvailableCapacity());

        // Corrupt Redis to 0 (simulating cold cache, eviction, or crash)
        stringRedisTemplate.opsForValue().set(
                RedisKeyBuilder.buildResourceCapacityKey(resource.getId()), "0"
        );

        // Run Reconciliation Worker
        ReconciliationWorker.ReconciliationSummary summary = reconciliationWorker.processReconciliation();
        assertTrue(summary.resourcesExamined() >= 1);

        // Verify Redis converged exactly to 30
        Integer convergedRedisCapacity = redisResourceService.getAvailableCapacity(resource.getId());
        assertNotNull(convergedRedisCapacity);
        assertEquals(expectedAvailable, convergedRedisCapacity.intValue(),
                "Redis capacity must converge to 50 - (5 + 10 + 5) = 30");

        // Hard Invariant Verification:
        // AVAILABLE + HELD + PAYMENT_PENDING + CONFIRMED == TOTAL CAPACITY
        int finalPostgresAvailable = resourceRepository.findById(resource.getId()).orElseThrow().getAvailableCapacity();
        int activeReservedSum = held.getQuantity() + conf.getQuantity() + pend.getQuantity(); // 5 + 10 + 5 = 20

        assertEquals(totalCapacity, finalPostgresAvailable + activeReservedSum,
                "Invariant violated: AVAILABLE + HELD + PAYMENT_PENDING + CONFIRMED must equal TOTAL CAPACITY");
        assertTrue(convergedRedisCapacity >= 0, "Redis capacity must not be negative");
        assertTrue(finalPostgresAvailable <= totalCapacity, "PostgreSQL available capacity cannot exceed total capacity");
        assertEquals(finalPostgresAvailable, convergedRedisCapacity.intValue(),
                "Redis and PostgreSQL must converge to the exact same state");
    }

    @Test
    @DisplayName("Scenario 7: State machine transitions strictly prevent invalid lifecycle progressions")
    void testStateMachineValidationRules() {
        Resource resource = createControlledResource("Scenario7-StateMachine", 20);

        Reservation res = reservationService.createReservation(
                resource.getId(), "user-sm-1", 2, "key-sm-" + UUID.randomUUID());

        // Initial: HELD
        assertEquals(ReservationStatus.HELD, res.getStatus());

        // Invalid: Direct transition from HELD -> CONFIRMED (must go through PAYMENT_PENDING)
        assertThrows(IllegalStateException.class, () ->
                reservationService.confirmReservation(res.getId()));

        // Valid: HELD -> PAYMENT_PENDING
        Reservation pending = reservationService.transitionToPaymentPending(res.getId());
        assertEquals(ReservationStatus.PAYMENT_PENDING, pending.getStatus());

        // Valid: PAYMENT_PENDING -> CONFIRMED
        Reservation confirmed = reservationService.confirmReservation(pending.getId());
        assertEquals(ReservationStatus.CONFIRMED, confirmed.getStatus());

        // Invalid: CONFIRMED cannot be cancelled via hold cancellation
        assertThrows(IllegalStateException.class, () ->
                reservationService.cancelReservation(confirmed.getId()));

        // Invalid: CONFIRMED cannot be expired
        confirmed.setExpiresAt(Instant.now().minusSeconds(100));
        reservationRepository.save(confirmed);
        Optional<Reservation> postExp = expirationService.expireReservation(confirmed.getId());
        assertTrue(postExp.isPresent());
        assertEquals(ReservationStatus.CONFIRMED, postExp.get().getStatus(),
                "CONFIRMED reservation must never be changed to EXPIRED");
    }

    @Test
    @DisplayName("Scenario 8: Payment decline restores capacity while technical processing failure propagates for DLQ retry")
    void testPaymentDeclineVsTechnicalProcessingFailure() {
        Resource resource = createControlledResource("Scenario8-PaymentModes", 20);

        // Case A: Payment Decline (Business Failure)
        Reservation declReservation = reservationService.createReservation(
                resource.getId(), "user-decl-1", 4, "key-decl-" + UUID.randomUUID());
        assertEquals(16, redisResourceService.getAvailableCapacity(resource.getId()));

        // Configure simulation mode: FORCE_DECLINE
        paymentSimulationService.setGlobalMode(PaymentSimulationService.SimulationMode.FORCE_DECLINE);
        paymentProcessorService.processReservationPayment(declReservation.getId());

        Reservation postDecl = reservationRepository.findById(declReservation.getId()).orElseThrow();
        assertEquals(ReservationStatus.CANCELLED, postDecl.getStatus(),
                "Payment decline must transition reservation to CANCELLED");
        assertEquals(20, redisResourceService.getAvailableCapacity(resource.getId()),
                "Payment decline must restore capacity in Redis");
        assertEquals(20, resourceRepository.findById(resource.getId()).orElseThrow().getAvailableCapacity(),
                "Payment decline must restore capacity in PostgreSQL");

        // Case B: Technical Gateway Failure (System Exception for Kafka DLQ retry)
        Reservation failReservation = reservationService.createReservation(
                resource.getId(), "user-fail-2", 4, "key-fail-" + UUID.randomUUID());
        assertEquals(16, redisResourceService.getAvailableCapacity(resource.getId()));

        // Configure simulation mode: FORCE_PROCESSING_FAILURE
        paymentSimulationService.setGlobalMode(PaymentSimulationService.SimulationMode.FORCE_PROCESSING_FAILURE);

        // Technical failure must throw PaymentProcessingException to allow Kafka retry / DLQ routing
        assertThrows(PaymentProcessingException.class, () ->
                paymentProcessorService.processReservationPayment(failReservation.getId()));

        // Reservation must NOT be cancelled or capacity restored on technical failure (awaits retry)
        Reservation postFail = reservationRepository.findById(failReservation.getId()).orElseThrow();
        assertEquals(ReservationStatus.HELD, postFail.getStatus(),
                "Technical failure must not transition reservation to CANCELLED before retries exhaust");
        assertEquals(16, redisResourceService.getAvailableCapacity(resource.getId()),
                "Technical failure must retain capacity during retry cycle");
    }

    @Test
    @DisplayName("Scenario 9: Redisson distributed lock enforces singleton worker execution on real Redis infrastructure")
    void testRedissonSingletonWorkerLockingWithRealRedis() throws Exception {
        String testLockName = "reservation-engine:worker:maintenance:singleton-" + UUID.randomUUID();

        // Worker instance 1 acquires the lock
        boolean acquiredInstance1 = distributedLockService.tryLock(testLockName);
        assertTrue(acquiredInstance1, "First worker instance must successfully acquire distributed lock");

        // Worker instance 2 (simulating a separate worker node on a different thread) attempts to acquire
        CompletableFuture<Boolean> instance2Attempt = CompletableFuture.supplyAsync(() ->
                distributedLockService.tryLock(testLockName)
        );
        boolean acquiredInstance2 = instance2Attempt.get(5, TimeUnit.SECONDS);
        assertFalse(acquiredInstance2, "Second worker instance must be rejected while lock is held (singleton worker enforcement)");

        // Worker instance 1 releases the lock
        distributedLockService.unlock(testLockName);

        // Now worker instance 2 can acquire it
        CompletableFuture<Boolean> postReleaseAttempt = CompletableFuture.supplyAsync(() ->
                distributedLockService.tryLock(testLockName)
        );
        boolean acquiredPostRelease = postReleaseAttempt.get(5, TimeUnit.SECONDS);
        assertTrue(acquiredPostRelease, "Worker instance should acquire lock after release");

        // Clean up: unlock on the thread that acquired it
        CompletableFuture.runAsync(() -> distributedLockService.unlock(testLockName)).get(5, TimeUnit.SECONDS);
    }
}

