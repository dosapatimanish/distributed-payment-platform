package com.paymentplatform.fxrate.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for FxRateEventPublisher - identical structure to wallet-service's
 * WalletEventPublisherTest (see testing-guide.md), since the two publisher classes are
 * deliberate copies of each other.
 */
@ExtendWith(MockitoExtension.class)
class FxRateEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private FxRateEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new FxRateEventPublisher(kafkaTemplate, new ObjectMapper());
    }

    @Test
    void publishRateLocked_sendsSerializedEventKeyedByTransactionId() {
        when(kafkaTemplate.send(eq("rate.locked"), eq("txn-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishRateLocked(new RateLockedEvent(
                "txn-1", "lock-1", "USD", "INR", new BigDecimal("83.0000"), new BigDecimal("100.00"), Instant.now()));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("rate.locked"), eq("txn-1"), payload.capture());
        assertThat(payload.getValue())
                .contains("\"transactionId\":\"txn-1\"")
                .contains("\"lockId\":\"lock-1\"");
    }

    @Test
    void publishRateLockFailed_sendsToLockFailedTopicKeyedByTransactionId() {
        when(kafkaTemplate.send(eq("rate.lock.failed"), eq("txn-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishRateLockFailed(new RateLockFailedEvent(
                "txn-1", "XXX", "YYY", BigDecimal.TEN, "No rate available for pair XXX/YYY", Instant.now()));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("rate.lock.failed"), eq("txn-1"), payload.capture());
        assertThat(payload.getValue()).contains("\"reason\":\"No rate available for pair XXX/YYY\"");
    }

    @Test
    void publish_kafkaSendFailsAsynchronously_doesNotPropagateToCaller() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka unreachable")));

        assertThatCode(() -> publisher.publishRateLocked(new RateLockedEvent(
                "txn-1", "lock-1", "USD", "INR", new BigDecimal("83.0000"), new BigDecimal("100.00"), Instant.now())))
                .doesNotThrowAnyException();
    }
}
