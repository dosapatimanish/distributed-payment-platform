package com.paymentplatform.merchantpayment.web;

import com.paymentplatform.merchantpayment.domain.MerchantPayment;
import com.paymentplatform.merchantpayment.domain.PaymentStatus;
import com.paymentplatform.merchantpayment.exception.InvalidPaymentStateException;
import com.paymentplatform.merchantpayment.exception.PaymentNotFoundException;
import com.paymentplatform.merchantpayment.idempotency.IdempotencyGuard;
import com.paymentplatform.merchantpayment.service.MerchantPaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MerchantPaymentController.class)
class MerchantPaymentControllerTest {

    private static final String IDEMPOTENCY_KEY = "test-key-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MerchantPaymentService paymentService;

    @MockitoBean
    private IdempotencyGuard idempotencyGuard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubIdempotencyGuardAsPassthrough() {
        when(idempotencyGuard.runIdempotent(anyString(), any(), any()))
                .thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(2)).get());
    }

    private MerchantPayment samplePayment(PaymentStatus status) {
        return new MerchantPayment("pay-1", "txn-1", "merchant-1", new BigDecimal("50.00"), "USD", "acq-ref-1", status);
    }

    @Test
    void pay_approved_returns201WithCompletedStatus() throws Exception {
        when(paymentService.pay(eq("0120260827000001"), eq("merchant-1"), any(), eq("USD")))
                .thenReturn(samplePayment(PaymentStatus.COMPLETED));

        mockMvc.perform(post("/api/v1/merchant-payments")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId":"0120260827000001","merchantId":"merchant-1","amount":50.00,"currency":"USD"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value("pay-1"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void pay_declined_stillReturns201WithFailedStatus() throws Exception {
        when(paymentService.pay(eq("0120260827000001"), eq("acct-decline"), any(), eq("USD")))
                .thenReturn(samplePayment(PaymentStatus.FAILED));

        mockMvc.perform(post("/api/v1/merchant-payments")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId":"0120260827000001","merchantId":"acct-decline","amount":50.00,"currency":"USD"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void pay_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/merchant-payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId":"0120260827000001","merchantId":"merchant-1","amount":50.00,"currency":"USD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void pay_currencyNotThreeChars_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/merchant-payments")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId":"0120260827000001","merchantId":"merchant-1","amount":50.00,"currency":"US"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void refund_completedPayment_returns200() throws Exception {
        when(paymentService.refund("pay-1")).thenReturn(samplePayment(PaymentStatus.REFUNDED));

        mockMvc.perform(post("/api/v1/merchant-payments/pay-1/refund"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void refund_notCompleted_returns409() throws Exception {
        when(paymentService.refund("pay-1")).thenThrow(new InvalidPaymentStateException("pay-1", PaymentStatus.FAILED));

        mockMvc.perform(post("/api/v1/merchant-payments/pay-1/refund"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_STATE"));
    }

    @Test
    void getPayment_found_returns200() throws Exception {
        when(paymentService.getPayment("pay-1")).thenReturn(samplePayment(PaymentStatus.COMPLETED));

        mockMvc.perform(get("/api/v1/merchant-payments/pay-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value("pay-1"));
    }

    @Test
    void getPayment_notFound_returns404() throws Exception {
        when(paymentService.getPayment("missing")).thenThrow(new PaymentNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/merchant-payments/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }
}
