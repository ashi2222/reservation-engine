package com.ashish.reservation_engine.dto;

import com.ashish.reservation_engine.entity.Reservation;
import com.ashish.reservation_engine.entity.ReservationStatus;

import java.time.Instant;

public class ReservationResponse {

    private Long id;
    private Long resourceId;
    private String userId;
    private Integer quantity;
    private String idempotencyKey;
    private ReservationStatus status;
    private Instant expiresAt;

    public ReservationResponse() {
    }

    public ReservationResponse(Long id, Long resourceId, String userId, Integer quantity, String idempotencyKey, ReservationStatus status, Instant expiresAt) {
        this.id = id;
        this.resourceId = resourceId;
        this.userId = userId;
        this.quantity = quantity;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.expiresAt = expiresAt;
    }

    public ReservationResponse(Reservation reservation) {
        this.id = reservation.getId();
        this.resourceId = reservation.getResourceId();
        this.userId = reservation.getUserId();
        this.quantity = reservation.getQuantity();
        this.idempotencyKey = reservation.getIdempotencyKey();
        this.status = reservation.getStatus();
        this.expiresAt = reservation.getExpiresAt();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    @Override
    public String toString() {
        return "ReservationResponse{" +
                "id=" + id +
                ", resourceId=" + resourceId +
                ", userId='" + userId + '\'' +
                ", quantity=" + quantity +
                ", idempotencyKey='" + idempotencyKey + '\'' +
                ", status=" + status +
                ", expiresAt=" + expiresAt +
                '}';
    }
}

