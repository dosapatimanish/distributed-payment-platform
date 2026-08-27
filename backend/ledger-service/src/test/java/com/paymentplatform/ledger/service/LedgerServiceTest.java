package com.paymentplatform.ledger.service;

import com.paymentplatform.ledger.domain.EntryType;
import com.paymentplatform.ledger.domain.LedgerEntry;
import com.paymentplatform.ledger.exception.InvalidLedgerEntriesException;
import com.paymentplatform.ledger.exception.LedgerConflictException;
import com.paymentplatform.ledger.repository.LedgerEntryRepository;
import com.paymentplatform.ledger.web.LedgerLineRequest;
import com.paymentplatform.ledger.web.PostEntriesRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private LedgerEntryRepository repository;

    private final DoubleEntryValidator validator = new DoubleEntryValidator();

    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(repository, validator);
    }

    private PostEntriesRequest balancedRequest() {
        return new PostEntriesRequest("txn-1", List.of(
                new LedgerLineRequest("wallet-A", EntryType.DEBIT, new BigDecimal("100.00"), "USD", new BigDecimal("400.00")),
                new LedgerLineRequest("wallet-B", EntryType.CREDIT, new BigDecimal("100.00"), "USD", new BigDecimal("600.00"))
        ));
    }

    @Test
    void postEntries_balancedPosting_savesBothLegs() {
        when(repository.existsByTransactionId("txn-1")).thenReturn(false);
        when(repository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        List<LedgerEntry> saved = ledgerService.postEntries(balancedRequest());

        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(LedgerEntry::getEntryType)
                .containsExactlyInAnyOrder(EntryType.DEBIT, EntryType.CREDIT);
    }

    @Test
    void postEntries_transactionAlreadyPosted_throwsConflictAndNeverValidatesOrSaves() {
        when(repository.existsByTransactionId("txn-1")).thenReturn(true);

        assertThatThrownBy(() -> ledgerService.postEntries(balancedRequest()))
                .isInstanceOf(LedgerConflictException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void postEntries_unbalancedPosting_throwsAndNeverSaves() {
        when(repository.existsByTransactionId("txn-1")).thenReturn(false);
        PostEntriesRequest unbalanced = new PostEntriesRequest("txn-1", List.of(
                new LedgerLineRequest("wallet-A", EntryType.DEBIT, new BigDecimal("100.00"), "USD", BigDecimal.ZERO),
                new LedgerLineRequest("wallet-B", EntryType.CREDIT, new BigDecimal("90.00"), "USD", BigDecimal.ZERO)
        ));

        assertThatThrownBy(() -> ledgerService.postEntries(unbalanced))
                .isInstanceOf(InvalidLedgerEntriesException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void getStatement_returnsWalletEntriesFromRepository() {
        LedgerEntry entry = new LedgerEntry("0120260827000001", "01", "011000000001", EntryType.DEBIT,
                new BigDecimal("100.00"), "USD", new BigDecimal("400.00"));
        when(repository.findByAccountNoOrderByCreatedAtAsc("011000000001")).thenReturn(List.of(entry));

        assertThat(ledgerService.getStatement("011000000001")).containsExactly(entry);
    }
}
