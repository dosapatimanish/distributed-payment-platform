package com.paymentplatform.wallet.web;

import com.paymentplatform.wallet.domain.ReservationStatus;
import com.paymentplatform.wallet.domain.Wallet;
import com.paymentplatform.wallet.domain.WalletReservation;
import com.paymentplatform.wallet.domain.WalletStatus;
import com.paymentplatform.wallet.exception.InsufficientFundsException;
import com.paymentplatform.wallet.exception.WalletNotFoundException;
import com.paymentplatform.wallet.idempotency.IdempotencyGuard;
import com.paymentplatform.wallet.idempotency.IdempotencyKeyInProgressException;
import com.paymentplatform.wallet.service.WalletService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test: only WalletController + GlobalExceptionHandler are loaded. WalletService and
 * IdempotencyGuard are Mockito doubles. Exercises request binding/validation and the HTTP
 * status/error-code mapping for each endpoint.
 */
@WebMvcTest(WalletController.class)
class WalletControllerTest {

    private static final String IDEMPOTENCY_KEY = "test-key-1";
    private static final String CIF = "1000000042";
    private static final String ACC = "011000000001";
    private static final String TXN = "0120260827000001";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WalletService walletService;

    @MockitoBean
    private IdempotencyGuard idempotencyGuard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubIdempotencyGuardAsPassthrough() {
        when(idempotencyGuard.runIdempotent(anyString(), any(), any()))
                .thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(2)).get());
    }

    private Wallet sampleWallet() {
        return new Wallet(ACC, CIF, "USD", new BigDecimal("100.0000"), WalletStatus.ACTIVE, false);
    }

    @Test
    void createWallet_validRequest_returns201() throws Exception {
        when(walletService.createWallet(eq(CIF), eq("USD"), eq(false))).thenReturn(sampleWallet());

        mockMvc.perform(post("/api/v1/wallets")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cif\":\"" + CIF + "\",\"currency\":\"USD\",\"highContention\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNo").value(ACC))
                .andExpect(jsonPath("$.balance").value(100.0));
    }

    @Test
    void createWallet_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cif\":\"" + CIF + "\",\"currency\":\"USD\",\"highContention\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createWallet_badCif_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/wallets")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cif\":\"12345\",\"currency\":\"USD\",\"highContention\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createWallet_currencyNotThreeChars_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/wallets")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cif\":\"" + CIF + "\",\"currency\":\"US\",\"highContention\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createWallet_idempotencyKeyInProgress_returns409() throws Exception {
        when(idempotencyGuard.runIdempotent(eq(IDEMPOTENCY_KEY), any(), any()))
                .thenThrow(new IdempotencyKeyInProgressException(IDEMPOTENCY_KEY));

        mockMvc.perform(post("/api/v1/wallets")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cif\":\"" + CIF + "\",\"currency\":\"USD\",\"highContention\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_IN_PROGRESS"));
    }

    @Test
    void getBalance_found_returns200() throws Exception {
        when(walletService.getBalance(ACC)).thenReturn(sampleWallet());

        mockMvc.perform(get("/api/v1/wallets/" + ACC + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.balance").value(100.0));
    }

    @Test
    void getBalance_notFound_returns404WithErrorCode() throws Exception {
        when(walletService.getBalance("999999999999")).thenThrow(new WalletNotFoundException("999999999999"));

        mockMvc.perform(get("/api/v1/wallets/999999999999/balance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/wallets/999999999999/balance"));
    }

    @Test
    void debit_insufficientFunds_returns422() throws Exception {
        when(walletService.debit(eq(ACC), any(BigDecimal.class), eq(TXN)))
                .thenThrow(new InsufficientFundsException(ACC, new BigDecimal("500"), new BigDecimal("100")));

        mockMvc.perform(post("/api/v1/wallets/" + ACC + "/debit")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500.00,\"transactionId\":\"" + TXN + "\"}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void debit_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/wallets/" + ACC + "/debit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00,\"transactionId\":\"" + TXN + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void debit_negativeAmount_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/wallets/" + ACC + "/debit")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":-5,\"transactionId\":\"" + TXN + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void debit_badTransactionId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/wallets/" + ACC + "/debit")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00,\"transactionId\":\"txn-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void credit_validRequest_returns200() throws Exception {
        Wallet credited = sampleWallet();
        credited.setBalance(new BigDecimal("150.0000"));
        when(walletService.credit(eq(ACC), any(BigDecimal.class), eq(TXN))).thenReturn(credited);

        mockMvc.perform(post("/api/v1/wallets/" + ACC + "/credit")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50.00,\"transactionId\":\"" + TXN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(150.0));
    }

    @Test
    void reserve_validRequest_returns201() throws Exception {
        WalletReservation reservation = new WalletReservation(
                "r-1", ACC, TXN, new BigDecimal("20.0000"), ReservationStatus.HELD,
                Instant.parse("2026-08-22T10:15:00Z"));
        when(walletService.reserveFunds(eq(ACC), any(BigDecimal.class), eq(TXN))).thenReturn(reservation);

        mockMvc.perform(post("/api/v1/wallets/" + ACC + "/reserve")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":20.00,\"transactionId\":\"" + TXN + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId").value("r-1"))
                .andExpect(jsonPath("$.status").value("HELD"));
    }

    @Test
    void captureReservation_validId_returns200() throws Exception {
        when(walletService.captureReservation("r-1")).thenReturn(sampleWallet());

        mockMvc.perform(post("/api/v1/wallets/reservations/r-1/capture")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNo").value(ACC));
    }

    @Test
    void releaseReservation_validId_returns200() throws Exception {
        when(walletService.releaseReservation("r-1")).thenReturn(sampleWallet());

        mockMvc.perform(post("/api/v1/wallets/reservations/r-1/release")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNo").value(ACC));
    }
}
