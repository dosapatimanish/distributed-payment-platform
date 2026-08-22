package com.paymentplatform.orchestrator.web;

import com.paymentplatform.orchestrator.domain.ConversionTransaction;
import com.paymentplatform.orchestrator.domain.SagaState;
import com.paymentplatform.orchestrator.exception.ConversionNotFoundException;
import com.paymentplatform.orchestrator.idempotency.IdempotencyGuard;
import com.paymentplatform.orchestrator.service.ConversionService;
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

/**
 * Slice test: only ConversionController + GlobalExceptionHandler are loaded, ConversionService
 * and IdempotencyGuard are Mockito doubles - same shape as the other two services' controller
 * tests.
 */
@WebMvcTest(ConversionController.class)
class ConversionControllerTest {

    private static final String IDEMPOTENCY_KEY = "test-key-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConversionService conversionService;

    @MockitoBean
    private IdempotencyGuard idempotencyGuard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubIdempotencyGuardAsPassthrough() {
        when(idempotencyGuard.runIdempotent(anyString(), any(), any()))
                .thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(2)).get());
    }

    private ConversionTransaction sampleTransaction(SagaState state) {
        ConversionTransaction txn = new ConversionTransaction(
                "txn-1", "user-1", "src-wallet", "dst-wallet", "USD", "INR", new BigDecimal("100.00"), IDEMPOTENCY_KEY);
        txn.setSagaState(state);
        return txn;
    }

    @Test
    void startConversion_validRequest_returns201() throws Exception {
        when(conversionService.startConversion(eq(IDEMPOTENCY_KEY), any())).thenReturn(sampleTransaction(SagaState.COMPLETED));

        mockMvc.perform(post("/api/v1/conversions")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user-1","sourceWalletId":"src-wallet","destWalletId":"dst-wallet","sourceCurrency":"USD","destCurrency":"INR","sourceAmount":100.00}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value("txn-1"))
                .andExpect(jsonPath("$.sagaState").value("COMPLETED"));
    }

    @Test
    void startConversion_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/conversions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user-1","sourceWalletId":"src-wallet","destWalletId":"dst-wallet","sourceCurrency":"USD","destCurrency":"INR","sourceAmount":100.00}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void startConversion_currencyNotThreeChars_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/conversions")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user-1","sourceWalletId":"src-wallet","destWalletId":"dst-wallet","sourceCurrency":"US","destCurrency":"INR","sourceAmount":100.00}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void getConversion_found_returns200() throws Exception {
        when(conversionService.getConversion("txn-1")).thenReturn(sampleTransaction(SagaState.RATE_LOCKED));

        mockMvc.perform(get("/api/v1/conversions/txn-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sagaState").value("RATE_LOCKED"));
    }

    @Test
    void getConversion_notFound_returns404() throws Exception {
        when(conversionService.getConversion("missing")).thenThrow(new ConversionNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/conversions/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSION_NOT_FOUND"));
    }
}
