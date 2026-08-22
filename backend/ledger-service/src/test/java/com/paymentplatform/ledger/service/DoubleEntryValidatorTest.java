package com.paymentplatform.ledger.service;

import com.paymentplatform.ledger.domain.EntryType;
import com.paymentplatform.ledger.exception.InvalidLedgerEntriesException;
import com.paymentplatform.ledger.web.LedgerLineRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DoubleEntryValidatorTest {

    private final DoubleEntryValidator validator = new DoubleEntryValidator();

    private LedgerLineRequest line(String walletId, EntryType type, String amount, String currency) {
        return new LedgerLineRequest(walletId, type, new BigDecimal(amount), currency, BigDecimal.TEN);
    }

    @Test
    void validate_balancedSameCurrencyPair_passes() {
        List<LedgerLineRequest> entries = List.of(
                line("wallet-A", EntryType.DEBIT, "100.00", "USD"),
                line("wallet-B", EntryType.CREDIT, "100.00", "USD"));

        assertThatCode(() -> validator.validate(entries)).doesNotThrowAnyException();
    }

    @Test
    void validate_emptyList_throws() {
        assertThatThrownBy(() -> validator.validate(List.of()))
                .isInstanceOf(InvalidLedgerEntriesException.class)
                .hasMessageContaining("at least one entry");
    }

    @Test
    void validate_onlyDebits_throws() {
        List<LedgerLineRequest> entries = List.of(
                line("wallet-A", EntryType.DEBIT, "100.00", "USD"),
                line("wallet-B", EntryType.DEBIT, "50.00", "USD"));

        assertThatThrownBy(() -> validator.validate(entries))
                .isInstanceOf(InvalidLedgerEntriesException.class)
                .hasMessageContaining("at least one DEBIT and one CREDIT");
    }

    @Test
    void validate_mismatchedAmountsSameCurrency_throws() {
        List<LedgerLineRequest> entries = List.of(
                line("wallet-A", EntryType.DEBIT, "100.00", "USD"),
                line("wallet-B", EntryType.CREDIT, "90.00", "USD"));

        assertThatThrownBy(() -> validator.validate(entries))
                .isInstanceOf(InvalidLedgerEntriesException.class)
                .hasMessageContaining("do not net to zero");
    }

    @Test
    void validate_multipleLegsNettingWithinCurrency_passes() {
        // One debit split across two credit legs in the same currency still nets to zero.
        List<LedgerLineRequest> entries = List.of(
                line("wallet-A", EntryType.DEBIT, "100.00", "USD"),
                line("wallet-B", EntryType.CREDIT, "60.00", "USD"),
                line("wallet-C", EntryType.CREDIT, "40.00", "USD"));

        assertThatCode(() -> validator.validate(entries)).doesNotThrowAnyException();
    }

    @Test
    void validate_differentCurrenciesEachUnbalanced_throws() {
        // Known limitation documented on the validator: cross-currency legs (e.g. an FX
        // conversion's source debit vs destination credit) cannot net against each other, and
        // each currency group here has only one leg, so both fail their own per-currency check.
        List<LedgerLineRequest> entries = List.of(
                line("wallet-A", EntryType.DEBIT, "100.00", "USD"),
                line("wallet-B", EntryType.CREDIT, "85.00", "EUR"));

        assertThatThrownBy(() -> validator.validate(entries))
                .isInstanceOf(InvalidLedgerEntriesException.class)
                .hasMessageContaining("do not net to zero");
    }
}
