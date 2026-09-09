package com.ashish.reservation_engine.messaging.publisher;

import com.ashish.reservation_engine.entity.OutboxEvent;
import com.ashish.reservation_engine.entity.OutboxStatus;
import com.ashish.reservation_engine.messaging.event.ReservationEventMessage;
import com.ashish.reservation_engine.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.kafka.topics.reservation-events:reservation-events}")
    private String topicName;

    @Value("${app.outbox.batch-size:50}")
    private int batchSize;

    @Value("${app.outbox.max-retries:5}")
    private int maxRetries;

    @Value("${app.outbox.send-timeout-seconds:5}")
    private long sendTimeoutSeconds;

    @Value("${app.outbox.enabled:true}")
    private boolean enabled;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${app.outbox.polling-interval-ms:1000}")
    public void schedulePublishPendingEvents() {
        if (!enabled) {
            return;
        }
        publishPendingEvents();
    }

    public int publishPendingEvents() {
        Pageable pageable = PageRequest.of(0, batchSize);
        List<OutboxEvent> pendingEvents = outboxEventRepository
                .findByStatusAndRetryCountLessThanOrderByCreatedAtAsc(OutboxStatus.PENDING, maxRetries, pageable);

        if (pendingEvents.isEmpty()) {
            return 0;
        }

        log.debug("Found {} unpublished outbox events to publish", pendingEvents.size());
        int publishedCount = 0;

        for (OutboxEvent event : pendingEvents) {
            boolean success = publishSingleEvent(event);
            if (success) {
                publishedCount++;
            }
        }

        return publishedCount;
    }

    public boolean publishSingleEvent(OutboxEvent event) {
        Long resourceId = resolveResourceId(event);
        if (resourceId == null) {
            log.error("Unable to resolve resourceId for outbox event id={}. Skipping event.", event.getId());
            outboxEventRepository.recordRetryFailure(event.getId(), "Unable to resolve resourceId for Kafka key");
            return false;
        }

        // Kafka message key MUST be based on resourceId to preserve per-resource partition ordering
        String kafkaKey = String.valueOf(resourceId);

        try {
            ReservationEventMessage message = ReservationEventMessage.fromOutboxEvent(event, resourceId);
            String messageJson = objectMapper.writeValueAsString(message);

            // Wait for Kafka broker acknowledgement to guarantee at-least-once delivery
            SendResult<String, String> sendResult = kafkaTemplate.send(topicName, kafkaKey, messageJson)
                    .get(sendTimeoutSeconds, TimeUnit.SECONDS);

            log.info("Published outbox event id={} to topic={} partition={} offset={}",
                    event.getId(),
                    topicName,
                    sendResult.getRecordMetadata().partition(),
                    sendResult.getRecordMetadata().offset());

            // Safe conditional update to avoid race conditions with multiple publisher instances
            int updated = outboxEventRepository.markAsPublishedConditionally(
                    event.getId(),
                    OutboxStatus.PENDING,
                    OutboxStatus.PUBLISHED,
                    Instant.now()
            );

            if (updated == 0) {
                log.warn("Outbox event id={} was already modified by another publisher instance", event.getId());
            }

            return true;
        } catch (Exception ex) {
            String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.error("Failed to publish outbox event id={} to Kafka topic {}: {}", event.getId(), topicName, errorMsg, ex);
            outboxEventRepository.recordRetryFailure(event.getId(), errorMsg);
            return false;
        }
    }

    private Long resolveResourceId(OutboxEvent event) {
        if (event.getResourceId() != null) {
            return event.getResourceId();
        }
        if (event.getPayload() != null && !event.getPayload().isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(event.getPayload());
                JsonNode resourceIdNode = root.get("resourceId");
                if (resourceIdNode != null && !resourceIdNode.isNull()) {
                    return resourceIdNode.asLong();
                }
            } catch (Exception e) {
                log.warn("Could not parse resourceId from payload for event id={}: {}", event.getId(), e.getMessage());
            }
        }
        return null;
    }
}

