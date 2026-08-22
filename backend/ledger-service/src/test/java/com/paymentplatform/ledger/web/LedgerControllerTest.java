package com.paymentplatform.ledger.web;

import com.paymentplatform.ledger.domain.EntryType;
import com.paymentplatform.ledger.domain.LedgerEntry;
import com.paymentplatform.ledger.exception.InvalidLedgerEntriesException;
import com.paymentplatform.ledger.exception.LedgerConflictException;
import com.paymentplatform.ledger.idempotency.IdempotencyGuard;
import com.paymentplatform.ledger.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LedgerController.class)
class LedgerControllerTest {

    private static final String IDEMPOTENCY_KEY = "test-key-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LedgerService ledgerService;

    @MockitoBean
    private IdempotencyGuard idempotencyGuard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubIdempotencyGuardAsPassthrough() {
        when(idempotencyGuard.runIdempotent(anyString(), any(), any()))
                .thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(2)).get());
    }

    private List<LedgerEntry> balancedPair() {
        return List.of(
                new LedgerEntry("entry-1", "txn-1", "wallet-A", EntryType.DEBIT,
                        new BigDecimal("100.00"), "USD", new BigDecimal("400.00")),
                new LedgerEntry("entry-2", "txn-1", "wallet-B", EntryType.CREDIT,
                        new BigDecimal("100.00"), "USD", new BigDecimal("600.00"))
        );
    }

    @Test
    void postEntries_balancedPosting_returns201WithBothLegs() throws Exception {
        when(ledgerService.postEntries(any())).thenReturn(balancedPair());

        mockMvc.perform(post("/api/v1/ledger/entries")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId":"txn-1","entries":[
                                  {"walletId":"wallet-A","entryType":"DEBIT","amount":100.00,"currency":"USD","balanceAfter":400.00},
                                  {"walletId":"wallet-B","entryType":"CREDIT","amount":100.00,"currency":"USD","balanceAfter":600.00}
                                ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].entryId").value("entry-1"));
    }

    @Test
    void postEntries_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/ledger/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId":"txn-1","entries":[
                                  {"walletId":"wallet-A","entryType":"DEBIT","amount":100.00,"currency":"USD","balanceAfter":400.00},
                                  {"walletId":"wallet-B","entryType":"CREDIT","amount":100.00,"currency":"USD","balanceAfter":600.00}
                                ]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void postEntries_emptyEntriesList_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/ledger/entries")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId":"txn-1","entries":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void postEntries_unbalancedPosting_returns400() throws Exception {
        when(ledgerService.postEntries(any())).thenThrow(new InvalidLedgerEntriesException("do not net to zero"));

        mockMvc.perform(post("/api/v1/ledger/entries")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId":"txn-1","entries":[
                                  {"walletId":"wallet-A","entryType":"DEBIT","amount":100.00,"currency":"USD","balanceAfter":400.00},
                                  {"walletId":"wallet-B","entryType":"CREDIT","amount":90.00,"currency":"USD","balanceAfter":600.00}
                                ]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LEDGER_ENTRIES"));
    }

    @Test
    void postEntries_duplicateTransaction_returns409() throws Exception {
        when(ledgerService.postEntries(any())).thenThrow(new LedgerConflictException("txn-1"));

        mockMvc.perform(post("/api/v1/ledger/entries")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId":"txn-1","entries":[
                                  {"walletId":"wallet-A","entryType":"DEBIT","amount":100.00,"currency":"USD","balanceAfter":400.00},
                                  {"walletId":"wallet-B","entryType":"CREDIT","amount":100.00,"currency":"USD","balanceAfter":600.00}
                                ]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEDGER_CONFLICT"));
    }

    @Test
    void getStatement_returnsWalletEntries() throws Exception {
        when(ledgerService.getStatement("wallet-A")).thenReturn(List.of(balancedPair().get(0)));

        mockMvc.perform(get("/api/v1/ledger/wallets/wallet-A/statement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value("wallet-A"))
                .andExpect(jsonPath("$.entries.length()").value(1));
    }

    @Test
    void getStatement_noEntries_returnsEmptyList() throws Exception {
        when(ledgerService.getStatement("wallet-Z")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/ledger/wallets/wallet-Z/statement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0));
    }
}
