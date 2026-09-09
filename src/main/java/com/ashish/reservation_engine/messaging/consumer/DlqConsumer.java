package com.ashish.reservation_engine.messaging.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class DlqConsumer {

    private static final Logger log = LoggerFactory.getLogger(DlqConsumer.class);

    @KafkaListener(
            topics = "${app.kafka.topics.reservation-events-dlq:reservation-events.DLT}",
            groupId = "${app.kafka.consumer.dlq-group-id:dlq-worker-group}"
    )
    public void consumeDlqMessage(
            ConsumerRecord<String, String> record,
            @Header(value = KafkaHeaders.ORIGINAL_TOPIC, required = false) Object originalTopic,
            @Header(value = KafkaHeaders.ORIGINAL_PARTITION, required = false) Object originalPartition,
            @Header(value = KafkaHeaders.ORIGINAL_OFFSET, required = false) Object originalOffset,
            @Header(value = KafkaHeaders.EXCEPTION_MESSAGE, required = false) Object exceptionMessage) {

        String topic = headerToString(originalTopic);
        String partition = headerToString(originalPartition);
        String offset = headerToString(originalOffset);
        String error = headerToString(exceptionMessage);

        log.error("DLQ RECORD RECEIVED: key={}, originalTopic={}, originalPartition={}, originalOffset={}, error={}, payload={}",
                record.key(), topic, partition, offset, error, record.value());
    }

    private String headerToString(Object header) {
        if (header == null) {
            return "unknown";
        }
        if (header instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return header.toString();
    }
}

