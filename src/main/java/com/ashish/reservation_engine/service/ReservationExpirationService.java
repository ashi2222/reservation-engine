package com.ashish.reservation_engine.service;

import com.ashish.reservation_engine.entity.Reservation;
import com.ashish.reservation_engine.entity.ReservationStatus;
import com.ashish.reservation_engine.entity.Resource;
import com.ashish.reservation_engine.redis.RedisKeyBuilder;
import com.ashish.reservation_engine.redis.RedisResourceService;
import com.ashish.reservation_engine.repository.ReservationRepository;
import com.ashish.reservation_engine.repository.ResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class ReservationExpirationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirationService.class);

    private final ReservationRepository reservationRepository;
    private final ResourceRepository resourceRepository;
    private final RedisResourceService redisResourceService;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;

    public ReservationExpirationService(ReservationRepository reservationRepository,
                                        ResourceRepository resourceRepository,
                                        RedisResourceService redisResourceService,
                                        StringRedisTemplate redisTemplate,
                                        PlatformTransactionManager transactionManager) {
        this.reservationRepository = reservationRepository;
        this.resourceRepository = resourceRepository;
        this.redisResourceService = redisResourceService;
        this.redisTemplate = redisTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public Optional<Reservation> expireReservation(Long reservationId) {
        if (reservationId == null) {
            throw new IllegalArgumentException("Reservation ID cannot be null");
        }

        // Lock reservation row with SELECT FOR UPDATE to prevent race with concurrent payment or cancellation
        Optional<Reservation> reservationOpt = reservationRepository.findByIdForUpdate(reservationId);
        if (reservationOpt.isEmpty()) {
            log.warn("Cannot expire reservation: not found with id={}", reservationId);
            return Optional.empty();
        }

        Reservation reservation = reservationOpt.get();

        // Expiration must ONLY restore capacity when the reservation is strictly HELD
        if (reservation.getStatus() != ReservationStatus.HELD) {
            log.info("Skipping expiration for reservation id={}. Status is already {} (only HELD can be expired)",
                    reservationId, reservation.getStatus());
            return Optional.of(reservation);
        }

        // Verify expiration condition has actually elapsed
        if (reservation.getExpiresAt() != null && reservation.getExpiresAt().isAfter(Instant.now())) {
            log.info("Skipping expiration for reservation id={}. expiresAt={} has not elapsed yet",
                    reservationId, reservation.getExpiresAt());
            return Optional.of(reservation);
        }

        // Acquire pessimistic lock on the associated Resource to prevent concurrent capacity modifications
        Resource resource = resourceRepository.findByIdForUpdate(reservation.getResourceId())
                .orElseThrow(() -> new NoSuchElementException("Resource not found with id: " + reservation.getResourceId()));

        // Double-check status after acquiring resource row lock
        if (reservation.getStatus() != ReservationStatus.HELD) {
            log.info("Reservation id={} status changed to {} while waiting for resource lock. Skipping expiration.",
                    reservationId, reservation.getStatus());
            return Optional.of(reservation);
        }

        // Transition reservation state to EXPIRED
        reservation.setStatus(ReservationStatus.EXPIRED);

        // Restore PostgreSQL capacity
        resource.setAvailableCapacity(resource.getAvailableCapacity() + reservation.getQuantity());

        resourceRepository.save(resource);
        Reservation savedReservation = reservationRepository.save(reservation);

        // Atomically restore capacity in Redis, bounded by resource total capacity
        redisResourceService.restoreCapacity(resource.getId(), reservation.getQuantity(), resource.getTotalCapacity());

        // Remove Redis hold key if still present
        if (reservation.getIdempotencyKey() != null) {
            String holdKey = RedisKeyBuilder.buildReservationHoldKey(reservation.getIdempotencyKey());
            redisTemplate.delete(holdKey);
        }

        log.info("Reservation id={} expired successfully. Restored capacity {} to resource id={}",
                reservationId, reservation.getQuantity(), resource.getId());

        return Optional.of(savedReservation);
    }

    public int expireExpiredHoldsBatch(Instant cutoff, int batchSize) {
        if (cutoff == null) {
            cutoff = Instant.now();
        }

        Pageable pageable = PageRequest.of(0, batchSize);
        List<Reservation> candidates = reservationRepository.findByStatusAndExpiresAtBefore(
                ReservationStatus.HELD, cutoff, pageable);

        if (candidates.isEmpty()) {
            return 0;
        }

        log.debug("Found {} candidate reservation holds eligible for expiration before {}", candidates.size(), cutoff);
        int expiredCount = 0;

        for (Reservation candidate : candidates) {
            try {
                // Execute each expiration in its own independent database transaction via TransactionTemplate
                Optional<Reservation> expiredOpt = transactionTemplate.execute(status ->
                        expireReservation(candidate.getId())
                );
                if (expiredOpt != null && expiredOpt.isPresent() && expiredOpt.get().getStatus() == ReservationStatus.EXPIRED) {
                    expiredCount++;
                }
            } catch (Exception ex) {
                log.error("Failed to expire candidate reservation id={}: {}", candidate.getId(), ex.getMessage(), ex);
            }
        }

        log.info("Batch processed {} candidates: expired {} reservation holds older than {}",
                candidates.size(), expiredCount, cutoff);
        return expiredCount;
    }

    public List<Reservation> expireExpiredHolds() {
        return expireExpiredHoldsBefore(Instant.now());
    }

    public List<Reservation> expireExpiredHoldsBefore(Instant cutoff) {
        if (cutoff == null) {
            cutoff = Instant.now();
        }

        List<Reservation> expiredCandidates = reservationRepository.findByStatusAndExpiresAtBefore(
                ReservationStatus.HELD, cutoff);

        List<Reservation> expiredResults = new ArrayList<>();
        for (Reservation candidate : expiredCandidates) {
            try {
                Optional<Reservation> expiredOpt = transactionTemplate.execute(status ->
                        expireReservation(candidate.getId())
                );
                if (expiredOpt != null && expiredOpt.isPresent() && expiredOpt.get().getStatus() == ReservationStatus.EXPIRED) {
                    expiredResults.add(expiredOpt.get());
                }
            } catch (Exception ex) {
                log.error("Failed to expire candidate reservation id={}", candidate.getId(), ex);
            }
        }

        log.info("Expired {} candidate reservation holds older than {}", expiredResults.size(), cutoff);
        return expiredResults;
    }
}
