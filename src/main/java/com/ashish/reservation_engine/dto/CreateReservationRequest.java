package com.ashish.reservation_engine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class CreateReservationRequest {

    @NotNull(message = "Resource ID is required")
    private Long resourceId;

    @NotBlank(message = "User ID must not be blank")
    private String userId;

    @NotNull(message = "Quantity is required")
    @Positive(message = "Quantity must be greater than zero")
    private Integer quantity;

    @NotBlank(message = "Idempotency key must not be blank")
    private String idempotencyKey;

    public CreateReservationRequest() {
    }

    public CreateReservationRequest(Long resourceId, String userId, Integer quantity, String idempotencyKey) {
        this.resourceId = resourceId;
        this.userId = userId;
        this.quantity = quantity;
        this.idempotencyKey = idempotencyKey;
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

    @Override
    public String toString() {
        return "CreateReservationRequest{" +
                "resourceId=" + resourceId +
                ", userId='" + userId + '\'' +
                ", quantity=" + quantity +
                ", idempotencyKey='" + idempotencyKey + '\'' +
                '}';
    }
}
