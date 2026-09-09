package com.ashish.reservation_engine.benchmark;

import java.util.Objects;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * Configuration for executing concurrent reservation load tests and benchmarks.
 * Supports configurable total requests, concurrency level, quantity, resource ID,
 * user ID generation, and idempotency key generation.
 */
public class BenchmarkConfig {

    private final Long resourceId;
    private final Integer resourceCapacity;
    private final int totalRequests;
    private final int concurrencyLevel;
    private final int quantity;
    private final IntFunction<String> userIdGenerator;
    private final IntFunction<String> idempotencyKeyGenerator;

    private BenchmarkConfig(Builder builder) {
        this.resourceId = Objects.requireNonNull(builder.resourceId, "resourceId must not be null");
        this.resourceCapacity = builder.resourceCapacity;
        if (builder.totalRequests <= 0) {
            throw new IllegalArgumentException("totalRequests must be greater than 0, got: " + builder.totalRequests);
        }
        if (builder.concurrencyLevel <= 0) {
            throw new IllegalArgumentException("concurrencyLevel must be greater than 0, got: " + builder.concurrencyLevel);
        }
        if (builder.quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0, got: " + builder.quantity);
        }
        this.totalRequests = builder.totalRequests;
        this.concurrencyLevel = builder.concurrencyLevel;
        this.quantity = builder.quantity;
        this.userIdGenerator = builder.userIdGenerator != null
                ? builder.userIdGenerator
                : index -> "load-user-" + index;
        this.idempotencyKeyGenerator = builder.idempotencyKeyGenerator != null
                ? builder.idempotencyKeyGenerator
                : index -> "idemp-" + index + "-" + UUID.randomUUID();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getResourceId() {
        return resourceId;
    }

    public Integer getResourceCapacity() {
        return resourceCapacity;
    }

    public int getTotalRequests() {
        return totalRequests;
    }

    public int getConcurrencyLevel() {
        return concurrencyLevel;
    }

    public int getQuantity() {
        return quantity;
    }

    public IntFunction<String> getUserIdGenerator() {
        return userIdGenerator;
    }

    public IntFunction<String> getIdempotencyKeyGenerator() {
        return idempotencyKeyGenerator;
    }

    public static class Builder {
        private Long resourceId;
        private Integer resourceCapacity;
        private int totalRequests = 100;
        private int concurrencyLevel = 10;
        private int quantity = 1;
        private IntFunction<String> userIdGenerator;
        private IntFunction<String> idempotencyKeyGenerator;

        public Builder resourceId(Long resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public Builder resourceCapacity(Integer resourceCapacity) {
            this.resourceCapacity = resourceCapacity;
            return this;
        }

        public Builder totalRequests(int totalRequests) {
            this.totalRequests = totalRequests;
            return this;
        }

        public Builder concurrencyLevel(int concurrencyLevel) {
            this.concurrencyLevel = concurrencyLevel;
            return this;
        }

        public Builder quantity(int quantity) {
            this.quantity = quantity;
            return this;
        }

        public Builder userIdGenerator(IntFunction<String> userIdGenerator) {
            this.userIdGenerator = userIdGenerator;
            return this;
        }

        public Builder idempotencyKeyGenerator(IntFunction<String> idempotencyKeyGenerator) {
            this.idempotencyKeyGenerator = idempotencyKeyGenerator;
            return this;
        }

        public BenchmarkConfig build() {
            return new BenchmarkConfig(this);
        }
    }
}

