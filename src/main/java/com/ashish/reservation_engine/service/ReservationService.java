package com.ashish.reservation_engine.service;

import com.ashish.reservation_engine.entity.Reservation;
import com.ashish.reservation_engine.entity.ReservationStatus;
import com.ashish.reservation_engine.entity.Resource;
import com.ashish.reservation_engine.repository.ReservationRepository;
import com.ashish.reservation_engine.repository.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class ReservationService {

    public record ReservationResult(Reservation reservation, boolean newlyCreated) {
    }

    private final ReservationRepository reservationRepository;
    private final ResourceRepository resourceRepository;

    public ReservationService(ReservationRepository reservationRepository, ResourceRepository resourceRepository) {
        this.reservationRepository = reservationRepository;
        this.resourceRepository = resourceRepository;
    }

    @Transactional
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

        Optional<Reservation> existingReservation = reservationRepository.findByIdempotencyKey(idempotencyKey);
        if (existingReservation.isPresent()) {
            return new ReservationResult(existingReservation.get(), false);
        }

        Resource resource = resourceRepository.findByIdForUpdate(resourceId)
                .orElseThrow(() -> new NoSuchElementException("Resource not found with id: " + resourceId));

        if (resource.getAvailableCapacity() < quantity) {
            throw new IllegalStateException("Insufficient capacity available for resource: " + resourceId);
        }

        resource.setAvailableCapacity(resource.getAvailableCapacity() - quantity);

        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10));
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
        return new ReservationResult(savedReservation, true);
    }

    @Transactional
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
        return reservationRepository.save(reservation);
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
