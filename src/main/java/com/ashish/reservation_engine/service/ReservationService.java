package com.ashish.reservation_engine.service;

import com.ashish.reservation_engine.dto.ReservationCreatedEvent;
import com.ashish.reservation_engine.entity.OutboxEvent;
import com.ashish.reservation_engine.entity.OutboxEventType;
import com.ashish.reservation_engine.entity.Reservation;
import com.ashish.reservation_engine.entity.ReservationStatus;
import com.ashish.reservation_engine.entity.Resource;
import com.ashish.reservation_engine.redis.RedisAdmissionResult;
import com.ashish.reservation_engine.redis.RedisAdmissionService;
import com.ashish.reservation_engine.redis.RedisResourceService;
import com.ashish.reservation_engine.repository.OutboxEventRepository;
import com.ashish.reservation_engine.repository.ReservationRepository;
import com.ashish.reservation_engine.repository.ResourceRepository;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final Duration HOLD_DURATION = Duration.ofMinutes(10);

    public record ReservationResult(Reservation reservation, boolean newlyCreated) {
    }

    private final ReservationRepository reservationRepository;
    private final ResourceRepository resourceRepository;
    private final RedisAdmissionService redisAdmissionService;
    private final RedisResourceService redisResourceService;
    private final TransactionTemplate transactionTemplate;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public ReservationService(ReservationRepository reservationRepository,
                              ResourceRepository resourceRepository,
                              RedisAdmissionService redisAdmissionService,
                              RedisResourceService redisResourceService,
                              PlatformTransactionManager transactionManager,
                              OutboxEventRepository outboxEventRepository,
                              ObjectMapper objectMapper) {
        this.reservationRepository = reservationRepository;
        this.resourceRepository = resourceRepository;
        this.redisAdmissionService = redisAdmissionService;
        this.redisResourceService = redisResourceService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public ReservationResult createReservationWithResult(Long resourceId, String userId, Integer quantity, String idempotencyKey) {
        if (resourceId == null) {
            throw new IllegalArgumentException("Resource ID cannot be null");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID cannot be null or blank");
        }
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key cannot be null or blank");
        }

        // Fast-path idempotency check in database
        Optional<Reservation> existingDbReservation = reservationRepository.findByIdempotencyKey(idempotencyKey);
        if (existingDbReservation.isPresent()) {
            return new ReservationResult(existingDbReservation.get(), false);
        }

        // Ensure resource capacity is initialized in Redis
        if (!redisResourceService.hasResourceCapacity(resourceId)) {
            Resource resource = resourceRepository.findById(resourceId)
                    .orElseThrow(() -> new NoSuchElementException("Resource not found with id: " + resourceId));
            redisResourceService.initializeCapacity(resourceId, resource.getAvailableCapacity());
        }

        // Hot-path atomic admission via Redis Lua script
        RedisAdmissionResult admissionResult = redisAdmissionService.admitReservation(
                resourceId, userId, quantity, idempotencyKey, HOLD_DURATION);

        switch (admissionResult.getStatus()) {
            case IDEMPOTENT:
                Optional<Reservation> existingOpt = reservationRepository.findByIdempotencyKey(idempotencyKey);
                if (existingOpt.isPresent()) {
                    return new ReservationResult(existingOpt.get(), false);
                }
                // If admitted in Redis but concurrent persistence is in flight, poll briefly
                for (int i = 0; i < 5; i++) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    existingOpt = reservationRepository.findByIdempotencyKey(idempotencyKey);
                    if (existingOpt.isPresent()) {
                        return new ReservationResult(existingOpt.get(), false);
                    }
                }
                throw new IllegalStateException("Idempotent reservation admitted in Redis but not found in PostgreSQL: " + idempotencyKey);

            case INSUFFICIENT_CAPACITY:
                throw new IllegalStateException("Insufficient capacity available for resource: " + resourceId);

            case RESOURCE_NOT_FOUND:
                throw new NoSuchElementException("Resource not found with id: " + resourceId);

            case INVALID_QUANTITY:
                throw new IllegalArgumentException(admissionResult.getMessage());

            case NEWLY_ADMITTED:
                try {
                    Reservation savedReservation = transactionTemplate.execute(status ->
                            persistReservationInDatabase(resourceId, userId, quantity, idempotencyKey)
                    );
                    return new ReservationResult(savedReservation, true);
                } catch (Exception ex) {
                    // Check if failure was caused by a concurrent DB insert race with the same idempotency key
                    Optional<Reservation> raceReservation = reservationRepository.findByIdempotencyKey(idempotencyKey);
                    if (raceReservation.isPresent()) {
                        log.warn("PostgreSQL unique constraint violation for idempotencyKey={}, returning existing reservation.", idempotencyKey);
                        // Compensate the redundant decrement in Redis
                        redisResourceService.restoreCapacity(resourceId, quantity, null);
                        return new ReservationResult(raceReservation.get(), false);
                    }

                    log.error("PostgreSQL persistence failed after successful Redis admission: resourceId={}, userId={}, quantity={}, idempotencyKey={}, error={}",
                            resourceId, userId, quantity, idempotencyKey, ex.getMessage(), ex);
                    redisAdmissionService.compensateAdmission(resourceId, quantity, idempotencyKey);
                    throw new IllegalStateException("Failed to persist reservation in database after Redis admission: " + ex.getMessage(), ex);
                }

            default:
                throw new IllegalStateException("Unexpected Redis admission outcome: " + admissionResult.getMessage());
        }
    }

    public Reservation persistReservationInDatabase(Long resourceId, String userId, Integer quantity, String idempotencyKey) {
        Resource resource = resourceRepository.findByIdForUpdate(resourceId)
                .orElseThrow(() -> new NoSuchElementException("Resource not found with id: " + resourceId));

        if (resource.getAvailableCapacity() < quantity) {
            throw new IllegalStateException("PostgreSQL capacity check failed for resource: " + resourceId);
        }

        resource.setAvailableCapacity(resource.getAvailableCapacity() - quantity);

        Instant expiresAt = Instant.now().plus(HOLD_DURATION);
        Reservation reservation = new Reservation(
                resourceId,
                userId,
                quantity,
                idempotencyKey,
                ReservationStatus.HELD,
                expiresAt
        );

        resourceRepository.save(resource);
        Reservation savedReservation = reservationRepository.save(reservation);

        try {
            ReservationCreatedEvent eventPayload = ReservationCreatedEvent.from(savedReservation);
            String payloadJson = objectMapper.writeValueAsString(eventPayload);
            OutboxEvent outboxEvent = new OutboxEvent(
                    "RESERVATION",
                    String.valueOf(savedReservation.getId()),
                    savedReservation.getResourceId(),
                    OutboxEventType.RESERVATION_CREATED,
                    payloadJson
            );
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            log.error("Failed to create outbox event for reservation id={}: {}", savedReservation.getId(), e.getMessage(), e);
            throw new IllegalStateException("Failed to create outbox event for reservation: " + e.getMessage(), e);
        }

        return savedReservation;
    }

    public Reservation createReservation(Long resourceId, String userId, Integer quantity, String idempotencyKey) {
        return createReservationWithResult(resourceId, userId, quantity, idempotencyKey).reservation();
    }

    public Reservation getReservationById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Reservation ID cannot be null");
        }
        return reservationRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Reservation not found with id: " + id));
    }

    @Transactional
    public Reservation cancelReservation(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Reservation ID cannot be null");
        }

        Reservation reservation = reservationRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Reservation not found with id: " + id));

        if (reservation.getStatus() != ReservationStatus.HELD) {
            throw new IllegalStateException("Only HELD reservations can be cancelled. Current status: " + reservation.getStatus());
        }

        Resource resource = resourceRepository.findByIdForUpdate(reservation.getResourceId())
                .orElseThrow(() -> new NoSuchElementException("Resource not found with id: " + reservation.getResourceId()));

        reservation.setStatus(ReservationStatus.CANCELLED);
        resource.setAvailableCapacity(resource.getAvailableCapacity() + reservation.getQuantity());

        resourceRepository.save(resource);
        Reservation saved = reservationRepository.save(reservation);

        // Synchronize restored capacity back to Redis using atomic bounded restoration
        redisResourceService.restoreCapacity(reservation.getResourceId(), reservation.getQuantity(), resource.getTotalCapacity());

        return saved;
    }

    @Transactional
    public Reservation transitionToPaymentPending(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Reservation ID cannot be null");
        }

        Reservation reservation = reservationRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Reservation not found with id: " + id));

        if (reservation.getStatus() != ReservationStatus.HELD) {
            throw new IllegalStateException("Cannot transition to PAYMENT_PENDING from status: " + reservation.getStatus());
        }

        reservation.setStatus(ReservationStatus.PAYMENT_PENDING);
        return reservationRepository.save(reservation);
    }

    @Transactional
    public Reservation markPaymentPending(Long id) {
        return transitionToPaymentPending(id);
    }

    @Transactional
    public Reservation confirmReservation(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Reservation ID cannot be null");
        }

        Reservation reservation = reservationRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Reservation not found with id: " + id));

        if (reservation.getStatus() != ReservationStatus.PAYMENT_PENDING) {
            throw new IllegalStateException("Cannot confirm reservation with status: " + reservation.getStatus());
        }

        reservation.setStatus(ReservationStatus.CONFIRMED);
        return reservationRepository.save(reservation);
    }

    @Transactional
    public Reservation transitionToConfirmed(Long id) {
        return confirmReservation(id);
    }
}
