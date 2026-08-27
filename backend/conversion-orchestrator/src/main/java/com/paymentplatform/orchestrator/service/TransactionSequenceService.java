package com.paymentplatform.orchestrator.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Hands out the next daily transaction sequence for a business date, from the {@code txn_sequence}
 * counter table. A new business date creates a fresh row starting at 1. MERGE + SELECT in one
 * transaction so the row lock serializes concurrent minters.
 */
@Service
public class TransactionSequenceService {

    private final EntityManager em;

    public TransactionSequenceService(EntityManager em) {
        this.em = em;
    }

    @Transactional
    public long next(LocalDate businessDate) {
        em.createNativeQuery("""
                MERGE INTO txn_sequence t
                USING (SELECT :bd AS business_date FROM dual) s
                ON (t.business_date = s.business_date)
                WHEN MATCHED THEN UPDATE SET t.next_val = t.next_val + 1
                WHEN NOT MATCHED THEN INSERT (business_date, next_val) VALUES (s.business_date, 1)
                """)
                .setParameter("bd", businessDate)
                .executeUpdate();

        Number seq = (Number) em.createNativeQuery(
                        "SELECT next_val FROM txn_sequence WHERE business_date = :bd")
                .setParameter("bd", businessDate)
                .getSingleResult();
        return seq.longValue();
    }
}
