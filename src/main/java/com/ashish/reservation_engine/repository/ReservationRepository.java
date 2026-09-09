package com.ashish.reservation_engine.repository;

import com.ashish.reservation_engine.entity.Reservation;
import com.ashish.reservation_engine.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    List<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, Instant cutoff);

    List<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, Instant cutoff, Pageable pageable);

    List<Reservation> findByResourceIdAndStatusIn(Long resourceId, Collection<ReservationStatus> statuses);
}

