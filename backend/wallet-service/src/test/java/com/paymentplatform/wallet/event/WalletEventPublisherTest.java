package com.paymentplatform.wallet.event;

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
 * Unit test for WalletEventPublisher - KafkaTemplate mocked with Mockito (Pattern 1 in
 * testing-guide.md, same idea as mocking a repository), a real ObjectMapper since JSON
 * serialization is cheap and pure (Pattern 3).
 */
@ExtendWith(MockitoExtension.class)
class WalletEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private WalletEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new WalletEventPublisher(kafkaTemplate, new ObjectMapper());
    }

    @Test
    void publishDebited_sendsSerializedEventKeyedByWalletId() {
        when(kafkaTemplate.send(eq("wallet.debited"), eq("w-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishDebited(new WalletDebitedEvent(
                "w-1", "txn-1", new BigDecimal("10.00"), new BigDecimal("90.00"), Instant.parse("2026-08-22T10:00:00Z")));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("wallet.debited"), eq("w-1"), payload.capture());
        assertThat(payload.getValue())
                .contains("\"walletId\":\"w-1\"")
                .contains("\"transactionId\":\"txn-1\"");
    }

    @Test
    void publishDebitFailed_sendsToDebitFailedTopicKeyedByWalletId() {
        when(kafkaTemplate.send(eq("wallet.debit.failed"), eq("w-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishDebitFailed(new WalletDebitFailedEvent(
                "w-1", "txn-1", new BigDecimal("500.00"), "Insufficient funds", Instant.now()));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("wallet.debit.failed"), eq("w-1"), payload.capture());
        assertThat(payload.getValue()).contains("\"reason\":\"Insufficient funds\"");
    }

    @Test
    void publishCredited_sendsToCreditedTopicKeyedByWalletId() {
        when(kafkaTemplate.send(eq("wallet.credited"), eq("w-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishCredited(new WalletCreditedEvent(
                "w-1", "txn-1", new BigDecimal("50.00"), new BigDecimal("150.00"), Instant.now()));

        verify(kafkaTemplate).send(eq("wallet.credited"), eq("w-1"), anyString());
    }

    @Test
    void publish_kafkaSendFailsAsynchronously_doesNotPropagateToCaller() {
        // Kafka being unreachable must not fail the HTTP request whose DB work already
        // committed - see WalletEventPublisher's class javadoc.
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka unreachable")));

        assertThatCode(() -> publisher.publishDebited(new WalletDebitedEvent(
                "w-1", "txn-1", new BigDecimal("10.00"), new BigDecimal("90.00"), Instant.now())))
                .doesNotThrowAnyException();
    }
}
