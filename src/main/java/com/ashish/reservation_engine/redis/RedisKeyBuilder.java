package com.ashish.reservation_engine.redis;

public final class RedisKeyBuilder {

    private static final String RESOURCE_CAPACITY_PREFIX = "resource:";
    private static final String RESOURCE_CAPACITY_SUFFIX = ":capacity";
    private static final String IDEMPOTENCY_PREFIX = "idempotency:reservation:";
    private static final String HOLD_PREFIX = "reservation:hold:";

    private RedisKeyBuilder() {
    }

    public static String buildResourceCapacityKey(Long resourceId) {
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId cannot be null");
        }
        return RESOURCE_CAPACITY_PREFIX + resourceId + RESOURCE_CAPACITY_SUFFIX;
    }

    public static String buildIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey cannot be null or blank");
        }
        return IDEMPOTENCY_PREFIX + idempotencyKey;
    }

    public static String buildReservationHoldKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey cannot be null or blank");
        }
        return HOLD_PREFIX + idempotencyKey;
    }

    public static String buildReservationHoldKeyById(Long reservationId) {
        if (reservationId == null) {
            throw new IllegalArgumentException("reservationId cannot be null");
        }
        return HOLD_PREFIX + "id:" + reservationId;
    }
}
