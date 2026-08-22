package com.paymentplatform.wallet.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes wallet lifecycle events to Kafka (design doc 6.5, 6.3.1). Partition key is
 * {@code walletId} for every topic here, matching the design doc's topic table - so all events
 * for one wallet land on the same partition and are delivered to a consumer in order.
 *
 * <b>Deliberate simplification</b>: this publishes directly, right after the DB transaction
 * that produced the event has already committed (called from {@code WalletService} after
 * {@code applyMutation}/{@code TransactionTemplate.execute} returns). There is a real, accepted
 * gap here: if the process crashes between that commit and this Kafka send, the event is lost
 * even though the mutation genuinely happened. Closing that gap is exactly what the
 * Transactional Outbox pattern is for - still a deferred piece (see implementation notes),
 * deliberately not built alongside this first publishing pass.
 */
@Component
public class WalletEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WalletEventPublisher.class);

    private static final String TOPIC_DEBITED = "wallet.debited";
    private static final String TOPIC_DEBIT_FAILED = "wallet.debit.failed";
    private static final String TOPIC_CREDITED = "wallet.credited";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public WalletEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishDebited(WalletDebitedEvent event) {
        send(TOPIC_DEBITED, event.walletId(), event);
    }

    public void publishDebitFailed(WalletDebitFailedEvent event) {
        send(TOPIC_DEBIT_FAILED, event.walletId(), event);
    }

    public void publishCredited(WalletCreditedEvent event) {
        send(TOPIC_CREDITED, event.walletId(), event);
    }

    private void send(String topic, String key, Object event) {
        String payload = objectMapper.writeValueAsString(event);
        kafkaTemplate.send(topic, key, payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        // No consumer exists yet to depend on this, and no outbox/retry backs it
                        // (see class javadoc) - a failed send is logged, not thrown, so a Kafka
                        // hiccup never fails the HTTP request that already committed successfully.
                        log.warn("Failed to publish to topic {} (key={}): {}", topic, key, ex.getMessage());
                    }
                });
    }
}
