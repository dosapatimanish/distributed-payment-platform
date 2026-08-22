package com.paymentplatform.fxrate.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes rate-lock lifecycle events to Kafka (design doc 6.5). Partition key is
 * {@code transactionId} for both topics here, matching the design doc's topic table - so every
 * event for one conversion transaction lands on the same partition and is delivered in order.
 *
 * Same deliberate simplification and same reasoning as wallet-service's WalletEventPublisher
 * (direct publish after commit, no outbox yet, async send failures logged not thrown) - see its
 * javadoc; this class is a deliberate independent copy of the same pattern, consistent with how
 * IdempotencyGuard is mirrored rather than shared between these two services.
 */
@Component
public class FxRateEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(FxRateEventPublisher.class);

    private static final String TOPIC_RATE_LOCKED = "rate.locked";
    private static final String TOPIC_RATE_LOCK_FAILED = "rate.lock.failed";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public FxRateEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishRateLocked(RateLockedEvent event) {
        send(TOPIC_RATE_LOCKED, event.transactionId(), event);
    }

    public void publishRateLockFailed(RateLockFailedEvent event) {
        send(TOPIC_RATE_LOCK_FAILED, event.transactionId(), event);
    }

    private void send(String topic, String key, Object event) {
        String payload = objectMapper.writeValueAsString(event);
        kafkaTemplate.send(topic, key, payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Failed to publish to topic {} (key={}): {}", topic, key, ex.getMessage());
                    }
                });
    }
}
