package com.paymentplatform.fxrate.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/**
 * Hands out identifiable, sequence-based ids ({@code PREFIX + 10-digit Oracle sequence value},
 * e.g. {@code LK0000000001}) instead of random UUIDs, so rows can be ordered/traced by id
 * (backend-documents/identifier-scheme.md). Deliberate independent copy per service, like
 * {@code IdempotencyGuard}.
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
