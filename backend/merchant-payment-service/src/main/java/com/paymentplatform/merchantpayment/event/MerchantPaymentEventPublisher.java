package com.paymentplatform.merchantpayment.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes payment lifecycle events to Kafka (design doc 6.5). Partition key is
 * {@code transactionId} for both topics, matching the design doc's topic table - so every event
 * for one conversion transaction lands on the same partition as that transaction's
 * wallet/rate-lock events and is delivered in order.
 *
 * Same deliberate simplification and reasoning as the other services' event publishers (direct
 * publish after commit, no outbox yet, async send failures logged not thrown) - see
 * WalletEventPublisher's javadoc for the full reasoning; this is a deliberate independent copy
 * of the same pattern. No {@code refund} event exists - not in the design doc's topic table,
 * same as fx-rate-service's release and wallet-service's credit having no dedicated failure
 * topic either.
 */
@Component
public class MerchantPaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(MerchantPaymentEventPublisher.class);

    private static final String TOPIC_PAYMENT_COMPLETED = "payment.completed";
    private static final String TOPIC_PAYMENT_FAILED = "payment.failed";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public MerchantPaymentEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishCompleted(PaymentCompletedEvent event) {
        send(TOPIC_PAYMENT_COMPLETED, event.transactionId(), event);
    }

    public void publishFailed(PaymentFailedEvent event) {
        send(TOPIC_PAYMENT_FAILED, event.transactionId(), event);
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
