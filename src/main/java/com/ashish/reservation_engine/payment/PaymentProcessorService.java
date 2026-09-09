package com.ashish.reservation_engine.payment;

import com.ashish.reservation_engine.entity.Reservation;
import com.ashish.reservation_engine.entity.ReservationStatus;
import com.ashish.reservation_engine.entity.Resource;
import com.ashish.reservation_engine.redis.RedisKeyBuilder;
import com.ashish.reservation_engine.redis.RedisResourceService;
import com.ashish.reservation_engine.repository.ReservationRepository;
import com.ashish.reservation_engine.repository.ResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class PaymentProcessorService {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessorService.class);

    private final ReservationRepository reservationRepository;
    private final ResourceRepository resourceRepository;
    private final RedisResourceService redisResourceService;
    private final StringRedisTemplate stringRedisTemplate;
    private final PaymentSimulationService paymentSimulationService;

    public PaymentProcessorService(ReservationRepository reservationRepository,
                                  ResourceRepository resourceRepository,
                                  RedisResourceService redisResourceService,
                                  StringRedisTemplate stringRedisTemplate,
                                  PaymentSimulationService paymentSimulationService) {
        this.reservationRepository = reservationRepository;
        this.resourceRepository = resourceRepository;
        this.redisResourceService = redisResourceService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.paymentSimulationService = paymentSimulationService;
    }

    @Transactional
    public void processReservationPayment(Long reservationId) {
        if (reservationId == null) {
            log.warn("Cannot process payment for null reservationId");
            return;
        }

        // 1 & 2. Load reservation and acquire PostgreSQL row lock (SELECT FOR UPDATE)
        Optional<Reservation> opt = reservationRepository.findByIdForUpdate(reservationId);
        if (opt.isEmpty()) {
            log.error("Reservation not found for id={}. Cannot process payment.", reservationId);
            throw new NoSuchElementException("Reservation not found with id: " + reservationId);
        }

        Reservation reservation = opt.get();

        // 3. Idempotency and State safety checks:
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            log.info("Reservation id={} is already CONFIRMED. Idempotent skip.", reservationId);
            return;
        }
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            log.info("Reservation id={} is already CANCELLED. Idempotent skip - capacity will NOT be restored again.", reservationId);
            return;
        }
        if (reservation.getStatus() == ReservationStatus.EXPIRED) {
            log.info("Reservation id={} is EXPIRED. Skipping payment processing.", reservationId);
            return;
        }

        if (reservation.getStatus() != ReservationStatus.HELD && reservation.getStatus() != ReservationStatus.PAYMENT_PENDING) {
            log.warn("Reservation id={} in unexpected status {}. Skipping payment.", reservationId, reservation.getStatus());
            return;
        }

        // Simulate payment (can throw PaymentProcessingException which will trigger Kafka retry -> DLQ)
        PaymentResult paymentResult = paymentSimulationService.processPayment(
                reservation.getId(), reservation.getUserId(), reservation.getQuantity());

        if (paymentResult.successful()) {
            handlePaymentSuccess(reservation);
        } else {
            handlePaymentDecline(reservation, paymentResult.declineReason());
        }
    }

    private void handlePaymentSuccess(Reservation reservation) {
        // State transitions: HELD -> PAYMENT_PENDING -> CONFIRMED
        reservation.setStatus(ReservationStatus.PAYMENT_PENDING);
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(reservation);

        // Delete Redis temporary hold key
        if (reservation.getIdempotencyKey() != null) {
            String holdKey = RedisKeyBuilder.buildReservationHoldKey(reservation.getIdempotencyKey());
            stringRedisTemplate.delete(holdKey);
        }

        log.info("Reservation id={} successfully CONFIRMED after payment. Redis capacity remains consumed.", reservation.getId());
    }

    private void handlePaymentDecline(Reservation reservation, String reason) {
        log.warn("Handling payment decline for reservation id={}. Reason: {}", reservation.getId(), reason);

        // Lock resource in PostgreSQL to update available capacity
        Resource resource = resourceRepository.findByIdForUpdate(reservation.getResourceId())
                .orElseThrow(() -> new NoSuchElementException("Resource not found with id: " + reservation.getResourceId()));

        reservation.setStatus(ReservationStatus.CANCELLED);
        resource.setAvailableCapacity(resource.getAvailableCapacity() + reservation.getQuantity());

        resourceRepository.save(resource);
        reservationRepository.save(reservation);

        // Restore Redis capacity atomically, bounded by totalCapacity
        redisResourceService.restoreCapacity(reservation.getResourceId(), reservation.getQuantity(), resource.getTotalCapacity());

        // Remove Redis hold key
        if (reservation.getIdempotencyKey() != null) {
            String holdKey = RedisKeyBuilder.buildReservationHoldKey(reservation.getIdempotencyKey());
            stringRedisTemplate.delete(holdKey);
        }

        log.info("Reservation id={} CANCELLED due to payment decline. Restored capacity {} to resource id={}",
                reservation.getId(), reservation.getQuantity(), resource.getId());
    }
}

