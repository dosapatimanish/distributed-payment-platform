package com.paymentplatform.wallet.web;

import com.paymentplatform.wallet.domain.ReservationStatus;
import com.paymentplatform.wallet.domain.Wallet;
import com.paymentplatform.wallet.domain.WalletReservation;
import com.paymentplatform.wallet.domain.WalletStatus;
import com.paymentplatform.wallet.exception.InsufficientFundsException;
import com.paymentplatform.wallet.exception.WalletNotFoundException;
import com.paymentplatform.wallet.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test: only WalletController + GlobalExceptionHandler are loaded ({@code @WebMvcTest}
 * scans controllers and {@code @RestControllerAdvice} beans), WalletService is a Mockito
 * double. Exercises request binding/validation and the HTTP status/error-code mapping for each
 * endpoint - not the business logic itself, which WalletServiceTest already covers.
 */
@WebMvcTest(WalletController.class)
class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WalletService walletService;

    private Wallet sampleWallet() {
        Wallet wallet = new Wallet("w-1", "user-1", "USD", new BigDecimal("100.0000"), WalletStatus.ACTIVE, false);
        return wallet;
    }

    @Test
    void createWallet_validRequest_returns201() throws Exception {
        when(walletService.createWallet(eq("user-1"), eq("USD"), eq(false))).thenReturn(sampleWallet());

        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user-1","currency":"USD","highContention":false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.walletId").value("w-1"))
                .andExpect(jsonPath("$.balance").value(100.0));
    }

    @Test
    void createWallet_blankUserId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"","currency":"USD","highContention":false}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createWallet_currencyNotThreeChars_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user-1","currency":"US","highContention":false}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void getBalance_found_returns200() throws Exception {
        when(walletService.getBalance("w-1")).thenReturn(sampleWallet());

        mockMvc.perform(get("/api/v1/wallets/w-1/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.balance").value(100.0));
    }

    @Test
    void getBalance_notFound_returns404WithErrorCode() throws Exception {
        when(walletService.getBalance("missing")).thenThrow(new WalletNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/wallets/missing/balance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/wallets/missing/balance"));
    }

    @Test
    void debit_insufficientFunds_returns422() throws Exception {
        when(walletService.debit(eq("w-1"), any(BigDecimal.class), eq("txn-1")))
                .thenThrow(new InsufficientFundsException("w-1", new BigDecimal("500"), new BigDecimal("100")));

        mockMvc.perform(post("/api/v1/wallets/w-1/debit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":500.00,"transactionId":"txn-1"}
                                """))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void debit_negativeAmount_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/wallets/w-1/debit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":-5,"transactionId":"txn-1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void credit_validRequest_returns200() throws Exception {
        Wallet credited = sampleWallet();
        credited.setBalance(new BigDecimal("150.0000"));
        when(walletService.credit(eq("w-1"), any(BigDecimal.class), eq("txn-2"))).thenReturn(credited);

        mockMvc.perform(post("/api/v1/wallets/w-1/credit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":50.00,"transactionId":"txn-2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(150.0));
    }

    @Test
    void reserve_validRequest_returns201() throws Exception {
        WalletReservation reservation = new WalletReservation(
                "r-1", "w-1", "txn-3", new BigDecimal("20.0000"), ReservationStatus.HELD,
                Instant.parse("2026-08-22T10:15:00Z"));
        when(walletService.reserveFunds(eq("w-1"), any(BigDecimal.class), eq("txn-3"))).thenReturn(reservation);

        mockMvc.perform(post("/api/v1/wallets/w-1/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":20.00,"transactionId":"txn-3"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId").value("r-1"))
                .andExpect(jsonPath("$.status").value("HELD"));
    }

    @Test
    void captureReservation_validId_returns200() throws Exception {
        when(walletService.captureReservation("r-1")).thenReturn(sampleWallet());

        mockMvc.perform(post("/api/v1/wallets/reservations/r-1/capture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value("w-1"));
    }

    @Test
    void releaseReservation_validId_returns200() throws Exception {
        when(walletService.releaseReservation("r-1")).thenReturn(sampleWallet());

        mockMvc.perform(post("/api/v1/wallets/reservations/r-1/release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value("w-1"));
    }
}
