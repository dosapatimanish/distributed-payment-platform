package com.paymentplatform.merchantpayment.event;

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
 * Unit test for MerchantPaymentEventPublisher - identical structure to the other services' own
 * event publisher tests (see testing-guide.md Pattern 5), since all these publisher classes are
 * deliberate copies of each other.
 */
@ExtendWith(MockitoExtension.class)
class MerchantPaymentEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private MerchantPaymentEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new MerchantPaymentEventPublisher(kafkaTemplate, new ObjectMapper());
    }

    @Test
    void publishCompleted_sendsSerializedEventKeyedByTransactionId() {
        when(kafkaTemplate.send(eq("payment.completed"), eq("txn-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishCompleted(new PaymentCompletedEvent(
                "txn-1", "pay-1", new BigDecimal("50.00"), "USD", "acq-ref-1", Instant.now()));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("payment.completed"), eq("txn-1"), payload.capture());
        assertThat(payload.getValue())
                .contains("\"transactionId\":\"txn-1\"")
                .contains("\"acquirerRef\":\"acq-ref-1\"");
    }

    @Test
    void publishFailed_sendsToFailedTopicKeyedByTransactionId() {
        when(kafkaTemplate.send(eq("payment.failed"), eq("txn-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishFailed(new PaymentFailedEvent(
                "txn-1", "pay-1", new BigDecimal("50.00"), "USD", "Acquirer declined the charge", Instant.now()));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("payment.failed"), eq("txn-1"), payload.capture());
        assertThat(payload.getValue()).contains("\"reason\":\"Acquirer declined the charge\"");
    }

    @Test
    void publish_kafkaSendFailsAsynchronously_doesNotPropagateToCaller() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka unreachable")));

        assertThatCode(() -> publisher.publishCompleted(new PaymentCompletedEvent(
                "txn-1", "pay-1", new BigDecimal("50.00"), "USD", "acq-ref-1", Instant.now())))
                .doesNotThrowAnyException();
    }
}
