package com.ashish.reservation_engine.benchmark;

import java.util.concurrent.TimeUnit;

/**
 * Encapsulates the execution result and metrics of a single reservation request during a load test.
 */
public class RequestResult {

    private final int requestIndex;
    private final String userId;
    private final String idempotencyKey;
    private final RequestStatus status;
    private final int httpStatusCode;
    private final long latencyNanos;
    private final String message;
    private final Throwable error;

    public RequestResult(int requestIndex,
                         String userId,
                         String idempotencyKey,
                         RequestStatus status,
                         int httpStatusCode,
                         long latencyNanos,
                         String message,
                         Throwable error) {
        this.requestIndex = requestIndex;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.httpStatusCode = httpStatusCode;
        this.latencyNanos = latencyNanos;
        this.message = message;
        this.error = error;
    }

    public static RequestResult success(int index, String userId, String idempotencyKey, int statusCode, long latencyNanos, String message) {
        return new RequestResult(index, userId, idempotencyKey, RequestStatus.SUCCESS, statusCode, latencyNanos, message, null);
    }

    public static RequestResult rejected(int index, String userId, String idempotencyKey, int statusCode, long latencyNanos, String message) {
        return new RequestResult(index, userId, idempotencyKey, RequestStatus.REJECTED, statusCode, latencyNanos, message, null);
    }

    public static RequestResult failed(int index, String userId, String idempotencyKey, int statusCode, long latencyNanos, String message, Throwable error) {
        return new RequestResult(index, userId, idempotencyKey, RequestStatus.FAILED, statusCode, latencyNanos, message, error);
    }

    public int getRequestIndex() {
        return requestIndex;
    }

    public String getUserId() {
        return userId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    public long getLatencyNanos() {
        return latencyNanos;
    }

    public double getLatencyMs() {
        return latencyNanos / 1_000_000.0;
    }

    public long getLatencyMillis() {
        return TimeUnit.NANOSECONDS.toMillis(latencyNanos);
    }

    public String getMessage() {
        return message;
    }

    public Throwable getError() {
        return error;
    }

    public boolean isSuccess() {
        return status == RequestStatus.SUCCESS;
    }

    public boolean isRejected() {
        return status == RequestStatus.REJECTED;
    }

    public boolean isFailed() {
        return status == RequestStatus.FAILED;
    }

    @Override
    public String toString() {
        return "RequestResult{" +
                "requestIndex=" + requestIndex +
                ", userId='" + userId + '\'' +
                ", idempotencyKey='" + idempotencyKey + '\'' +
                ", status=" + status +
                ", httpStatusCode=" + httpStatusCode +
                ", latencyMs=" + String.format("%.2f", getLatencyMs()) +
                ", message='" + message + '\'' +
                '}';
    }
}

