package com.paymentplatform.fxrate.web;

import com.paymentplatform.fxrate.domain.FxRateLock;
import com.paymentplatform.fxrate.domain.RateLockStatus;
import com.paymentplatform.fxrate.exception.RateLockNotActiveException;
import com.paymentplatform.fxrate.exception.RateLockNotFoundException;
import com.paymentplatform.fxrate.exception.UnsupportedCurrencyPairException;
import com.paymentplatform.fxrate.idempotency.IdempotencyGuard;
import com.paymentplatform.fxrate.idempotency.IdempotencyKeyInProgressException;
import com.paymentplatform.fxrate.service.FxRateCache;
import com.paymentplatform.fxrate.service.FxRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test: only FxRateController + GlobalExceptionHandler are loaded, FxRateService and
 * IdempotencyGuard are Mockito doubles. Same role as WalletControllerTest in wallet-service -
 * request binding, validation, and HTTP status/error-code mapping only.
 */
@WebMvcTest(FxRateController.class)
class FxRateControllerTest {

    private static final String IDEMPOTENCY_KEY = "test-key-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FxRateService fxRateService;

    @MockitoBean
    private IdempotencyGuard idempotencyGuard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubIdempotencyGuardAsPassthrough() {
        when(idempotencyGuard.runIdempotent(anyString(), any(), any()))
                .thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(2)).get());
    }

    private FxRateLock activeLock() {
        return new FxRateLock("lock-1", "txn-1", "USD", "INR",
                new BigDecimal("83.0000"), new BigDecimal("100.00"), RateLockStatus.ACTIVE,
                Instant.parse("2026-08-22T10:15:00Z"));
    }

    @Test
    void getCurrentRate_cachedPair_returns200() throws Exception {
        when(fxRateService.getCurrentRate("USD", "INR"))
                .thenReturn(new FxRateCache.RateSnapshot(new BigDecimal("83.0000"), "SIMULATED_FEED", Instant.now()));

        mockMvc.perform(get("/api/v1/fx/rates/USD/INR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCurrency").value("USD"))
                .andExpect(jsonPath("$.rate").value(83.0));
    }

    @Test
    void getCurrentRate_uncachedPair_returns404() throws Exception {
        when(fxRateService.getCurrentRate("XXX", "YYY"))
                .thenThrow(new UnsupportedCurrencyPairException("XXX", "YYY"));

        mockMvc.perform(get("/api/v1/fx/rates/XXX/YYY"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY_PAIR"));
    }

    @Test
    void lockRate_validRequest_returns201() throws Exception {
        when(fxRateService.lockRate(eq("txn-1"), eq("USD"), eq("INR"), any(BigDecimal.class)))
                .thenReturn(activeLock());

        mockMvc.perform(post("/api/v1/fx/rate-lock")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseCurrency":"USD","quoteCurrency":"INR","amount":100.00,"transactionId":"txn-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lockId").value("lock-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void lockRate_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/fx/rate-lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseCurrency":"USD","quoteCurrency":"INR","amount":100.00,"transactionId":"txn-1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void lockRate_idempotencyKeyInProgress_returns409() throws Exception {
        when(idempotencyGuard.runIdempotent(eq(IDEMPOTENCY_KEY), any(), any()))
                .thenThrow(new IdempotencyKeyInProgressException(IDEMPOTENCY_KEY));

        mockMvc.perform(post("/api/v1/fx/rate-lock")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseCurrency":"USD","quoteCurrency":"INR","amount":100.00,"transactionId":"txn-1"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_IN_PROGRESS"));
    }

    @Test
    void lockRate_blankTransactionId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/fx/rate-lock")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseCurrency":"USD","quoteCurrency":"INR","amount":100.00,"transactionId":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void lockRate_currencyCodeWrongLength_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/fx/rate-lock")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseCurrency":"US","quoteCurrency":"INR","amount":100.00,"transactionId":"txn-1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void consumeLock_activeLock_returns200() throws Exception {
        FxRateLock consumed = activeLock();
        consumed.setStatus(RateLockStatus.CONSUMED);
        when(fxRateService.consumeLock("lock-1")).thenReturn(consumed);

        mockMvc.perform(post("/api/v1/fx/rate-lock/lock-1/consume")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONSUMED"));
    }

    @Test
    void consumeLock_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/fx/rate-lock/lock-1/consume"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void consumeLock_alreadyConsumed_returns409() throws Exception {
        when(fxRateService.consumeLock("lock-1"))
                .thenThrow(new RateLockNotActiveException("lock-1", RateLockStatus.CONSUMED));

        mockMvc.perform(post("/api/v1/fx/rate-lock/lock-1/consume")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RATE_LOCK_NOT_ACTIVE"));
    }

    @Test
    void releaseLock_activeLock_returns200() throws Exception {
        FxRateLock released = activeLock();
        released.setStatus(RateLockStatus.RELEASED);
        when(fxRateService.releaseLock("lock-1")).thenReturn(released);

        mockMvc.perform(delete("/api/v1/fx/rate-lock/lock-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    void releaseLock_unknownId_returns404() throws Exception {
        when(fxRateService.releaseLock("missing")).thenThrow(new RateLockNotFoundException("missing"));

        mockMvc.perform(delete("/api/v1/fx/rate-lock/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RATE_LOCK_NOT_FOUND"));
    }
}
