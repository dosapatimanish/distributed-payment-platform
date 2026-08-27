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
    private static final String TXN = "0120260827000001";
    private static final String ACC_A = "011000000001";
    private static final String ACC_B = "031000000001";

    private static String posting(String txn, BigDecimal creditAmount) {
        return "{\"transactionId\":\"" + txn + "\",\"entries\":["
                + "{\"accountNo\":\"" + ACC_A + "\",\"entryType\":\"DEBIT\",\"amount\":100.00,\"currency\":\"USD\",\"balanceAfter\":400.00},"
                + "{\"accountNo\":\"" + ACC_B + "\",\"entryType\":\"CREDIT\",\"amount\":" + creditAmount + ",\"currency\":\"USD\",\"balanceAfter\":600.00}"
                + "]}";
    }

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
                new LedgerEntry(TXN, "01", ACC_A, EntryType.DEBIT,
                        new BigDecimal("100.00"), "USD", new BigDecimal("400.00")),
                new LedgerEntry(TXN, "02", ACC_B, EntryType.CREDIT,
                        new BigDecimal("100.00"), "USD", new BigDecimal("600.00"))
        );
    }

    @Test
    void postEntries_balancedPosting_returns201WithBothLegs() throws Exception {
        when(ledgerService.postEntries(any())).thenReturn(balancedPair());

        mockMvc.perform(post("/api/v1/ledger/entries")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(posting(TXN, new BigDecimal("100.00"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].entryNo").value("01"));
    }

    @Test
    void postEntries_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/ledger/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(posting(TXN, new BigDecimal("100.00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void postEntries_emptyEntriesList_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/ledger/entries")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":\"" + TXN + "\",\"entries\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void postEntries_badTransactionId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/ledger/entries")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(posting("txn-1", new BigDecimal("100.00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void postEntries_unbalancedPosting_returns400() throws Exception {
        when(ledgerService.postEntries(any())).thenThrow(new InvalidLedgerEntriesException("do not net to zero"));

        mockMvc.perform(post("/api/v1/ledger/entries")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(posting(TXN, new BigDecimal("90.00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LEDGER_ENTRIES"));
    }

    @Test
    void postEntries_duplicateTransaction_returns409() throws Exception {
        when(ledgerService.postEntries(any())).thenThrow(new LedgerConflictException(TXN));

        mockMvc.perform(post("/api/v1/ledger/entries")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(posting(TXN, new BigDecimal("100.00"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEDGER_CONFLICT"));
    }

    @Test
    void getStatement_returnsAccountEntries() throws Exception {
        when(ledgerService.getStatement(ACC_A)).thenReturn(List.of(balancedPair().get(0)));

        mockMvc.perform(get("/api/v1/ledger/wallets/" + ACC_A + "/statement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNo").value(ACC_A))
                .andExpect(jsonPath("$.entries.length()").value(1));
    }

    @Test
    void getStatement_noEntries_returnsEmptyList() throws Exception {
        when(ledgerService.getStatement("041000000009")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/ledger/wallets/041000000009/statement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0));
    }
}
