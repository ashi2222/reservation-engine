package com.ashish.reservation_engine.redis;

public class RedisAdmissionResult {

    public enum Status {
        NEWLY_ADMITTED,
        IDEMPOTENT,
        INSUFFICIENT_CAPACITY,
        RESOURCE_NOT_FOUND,
        INVALID_QUANTITY,
        UNKNOWN_ERROR
    }

    private final Status status;
    private final String payload;
    private final Integer remainingCapacity;
    private final String message;

    public RedisAdmissionResult(Status status, String payload, Integer remainingCapacity, String message) {
        this.status = status;
        this.payload = payload;
        this.remainingCapacity = remainingCapacity;
        this.message = message;
    }

    public static RedisAdmissionResult newlyAdmitted(String payload, Integer remainingCapacity) {
        return new RedisAdmissionResult(Status.NEWLY_ADMITTED, payload, remainingCapacity, null);
    }

    public static RedisAdmissionResult idempotent(String payload) {
        return new RedisAdmissionResult(Status.IDEMPOTENT, payload, null, null);
    }

    public static RedisAdmissionResult insufficientCapacity(Integer currentCapacity) {
        return new RedisAdmissionResult(Status.INSUFFICIENT_CAPACITY, null, currentCapacity, "Insufficient capacity available in Redis");
    }

    public static RedisAdmissionResult resourceNotFound(String message) {
        return new RedisAdmissionResult(Status.RESOURCE_NOT_FOUND, null, null, message);
    }

    public static RedisAdmissionResult invalidQuantity(String message) {
        return new RedisAdmissionResult(Status.INVALID_QUANTITY, null, null, message);
    }

    public static RedisAdmissionResult error(String message) {
        return new RedisAdmissionResult(Status.UNKNOWN_ERROR, null, null, message);
    }

    public Status getStatus() {
        return status;
    }

    public String getPayload() {
        return payload;
    }

    public Integer getRemainingCapacity() {
        return remainingCapacity;
    }

    public String getMessage() {
        return message;
    }

    public boolean isNewlyAdmitted() {
        return status == Status.NEWLY_ADMITTED;
    }

    public boolean isIdempotent() {
        return status == Status.IDEMPOTENT;
    }

    @Override
    public String toString() {
        return "RedisAdmissionResult{" +
                "status=" + status +
                ", payload='" + payload + '\'' +
                ", remainingCapacity=" + remainingCapacity +
                ", message='" + message + '\'' +
                '}';
    }
}
