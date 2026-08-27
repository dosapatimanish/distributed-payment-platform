package com.paymentplatform.merchantpayment.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/**
 * Identifiable, sequence-based ids ({@code PREFIX + 10-digit Oracle sequence value}, e.g.
 * {@code PM0000000001}) instead of random UUIDs (backend-documents/identifier-scheme.md).
 */
@Component
public class SequenceIds {

    private final EntityManager em;

    public SequenceIds(EntityManager em) {
        this.em = em;
    }

    public String next(String sequenceName, String prefix) {
        Number val = (Number) em.createNativeQuery("SELECT " + sequenceName + ".NEXTVAL FROM dual").getSingleResult();
        return prefix + String.format("%010d", val.longValue());
    }
}
