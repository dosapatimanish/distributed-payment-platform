package com.paymentplatform.wallet.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/**
 * Identifiable, sequence-based ids ({@code PREFIX + 10-digit Oracle sequence value}, e.g.
 * {@code RS0000000001}) instead of random UUIDs, so rows sort/trace by id
 * (backend-documents/identifier-scheme.md). Deliberate independent copy per service.
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
