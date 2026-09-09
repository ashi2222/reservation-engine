package com.ashish.reservation_engine.dto;

import com.ashish.reservation_engine.entity.Reservation;
import com.ashish.reservation_engine.entity.ReservationStatus;

import java.time.Instant;

public class ReservationCreatedEvent {

    private Long reservationId;
    private Long resourceId;
    private String userId;
    private Integer quantity;
    private String idempotencyKey;
    private ReservationStatus status;
    private Instant expiresAt;
    private Instant createdAt;

    public ReservationCreatedEvent() {
    }

    public ReservationCreatedEvent(Long reservationId, Long resourceId, String userId, Integer quantity,
                                   String idempotencyKey, ReservationStatus status, Instant expiresAt, Instant createdAt) {
        this.reservationId = reservationId;
        this.resourceId = resourceId;
        this.userId = userId;
        this.quantity = quantity;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public static ReservationCreatedEvent from(Reservation reservation) {
        return new ReservationCreatedEvent(
                reservation.getId(),
                reservation.getResourceId(),
                reservation.getUserId(),
                reservation.getQuantity(),
                reservation.getIdempotencyKey(),
                reservation.getStatus(),
                reservation.getExpiresAt(),
                Instant.now()
        );
    }

    public Long getReservationId() {
        return reservationId;
    }

    public void setReservationId(Long reservationId) {
        this.reservationId = reservationId;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "ReservationCreatedEvent{" +
                "reservationId=" + reservationId +
                ", resourceId=" + resourceId +
                ", userId='" + userId + '\'' +
                ", quantity=" + quantity +
                ", idempotencyKey='" + idempotencyKey + '\'' +
                ", status=" + status +
                ", expiresAt=" + expiresAt +
                ", createdAt=" + createdAt +
                '}';
    }
}

