package com.paymentplatform.wallet.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds a 12-char account number: {@code [currency short_code 2][CIF prefix 5][sequence 5]}
 * (backend-documents/identifier-scheme.md). The 5-digit sequence is per
 * {@code (short_code, CIF prefix)}, kept in the {@code account_sequence} counter table.
 *
 * The MERGE + SELECT run in one transaction so the row lock the MERGE takes is held until
 * commit - two concurrent calls for the same {@code (short_code, prefix)} serialize and get
 * distinct values. {@code UNIQUE(account_no)} on {@code wallet} is the hard backstop for the
 * documented case of two CIFs sharing a 5-digit prefix exhausting the same 99 999 pool.
 */
@Component
public class AccountNumberGenerator {

    private final EntityManager em;

    public AccountNumberGenerator(EntityManager em) {
        this.em = em;
    }

    @Transactional
    public String nextAccountNumber(String currencyShortCode, String cif) {
        String prefix = cif.substring(0, 5);
        em.createNativeQuery("""
                MERGE INTO account_sequence t
                USING (SELECT :cc AS currency_code, :pfx AS cif_prefix FROM dual) s
                ON (t.currency_code = s.currency_code AND t.cif_prefix = s.cif_prefix)
                WHEN MATCHED THEN UPDATE SET t.next_val = t.next_val + 1
                WHEN NOT MATCHED THEN INSERT (currency_code, cif_prefix, next_val)
                     VALUES (s.currency_code, s.cif_prefix, 1)
                """)
                .setParameter("cc", currencyShortCode)
                .setParameter("pfx", prefix)
                .executeUpdate();

        Number seq = (Number) em.createNativeQuery(
                        "SELECT next_val FROM account_sequence WHERE currency_code = :cc AND cif_prefix = :pfx")
                .setParameter("cc", currencyShortCode)
                .setParameter("pfx", prefix)
                .getSingleResult();

        return currencyShortCode + prefix + String.format("%05d", seq.longValue());
    }
}
