package com.ashish.reservation_engine.messaging.event;

import com.ashish.reservation_engine.entity.OutboxEvent;
import java.time.Instant;

public class ReservationEventMessage {

    private Long eventId;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private Long resourceId;
    private String eventPayload;
    private Instant createdAt;

    public ReservationEventMessage() {
    }

    public ReservationEventMessage(Long eventId, String eventType, String aggregateType, String aggregateId,
                                   Long resourceId, String eventPayload, Instant createdAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.resourceId = resourceId;
        this.eventPayload = eventPayload;
        this.createdAt = createdAt;
    }

    public static ReservationEventMessage fromOutboxEvent(OutboxEvent outboxEvent, Long resourceId) {
        return new ReservationEventMessage(
                outboxEvent.getId(),
                outboxEvent.getEventType() != null ? outboxEvent.getEventType().name() : null,
                outboxEvent.getAggregateType(),
                outboxEvent.getAggregateId(),
                resourceId,
                outboxEvent.getPayload(),
                outboxEvent.getCreatedAt()
        );
    }

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public String getEventPayload() {
        return eventPayload;
    }

    public void setEventPayload(String eventPayload) {
        this.eventPayload = eventPayload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "ReservationEventMessage{" +
                "eventId=" + eventId +
                ", eventType='" + eventType + '\'' +
                ", aggregateType='" + aggregateType + '\'' +
                ", aggregateId='" + aggregateId + '\'' +
                ", resourceId=" + resourceId +
                ", eventPayload='" + eventPayload + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}

