package com.paymentplatform.orchestrator.idempotency;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Redis-backed safe-retry guard for write endpoints (design doc §6.2.3) - identical mechanics
 * and the same only-cache-success simplification as wallet-service's and fx-rate-service's
 * IdempotencyGuard (see idempotency.md for the full reasoning). Deliberate independent copy,
 * not a shared library - the third instance of this same pattern, one per service.
 */
@Component
public class IdempotencyGuard {

    private static final String IN_PROGRESS_MARKER = "IN_PROGRESS";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final String keyPrefix;

    public IdempotencyGuard(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                             @Value("${orchestrator.idempotency.ttl-hours}") long ttlHours,
                             @Value("${orchestrator.idempotency.key-prefix}") String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofHours(ttlHours);
        this.keyPrefix = keyPrefix;
    }

    public <T> Optional<T> checkAndReserve(String idempotencyKey, Class<T> responseType) {
        String redisKey = redisKey(idempotencyKey);
        Boolean reserved = redisTemplate.opsForValue().setIfAbsent(redisKey, IN_PROGRESS_MARKER, ttl);
        if (Boolean.TRUE.equals(reserved)) {
            return Optional.empty();
        }
        String existing = redisTemplate.opsForValue().get(redisKey);
        if (existing == null || IN_PROGRESS_MARKER.equals(existing)) {
            throw new IdempotencyKeyInProgressException(idempotencyKey);
        }
        return Optional.of(objectMapper.readValue(existing, responseType));
    }

    public void confirm(String idempotencyKey, Object response) {
        redisTemplate.opsForValue().set(redisKey(idempotencyKey), objectMapper.writeValueAsString(response), ttl);
    }

    public void release(String idempotencyKey) {
        redisTemplate.delete(redisKey(idempotencyKey));
    }

    public <T> T runIdempotent(String idempotencyKey, Class<T> responseType, Supplier<T> action) {
        Optional<T> cached = checkAndReserve(idempotencyKey, responseType);
        if (cached.isPresent()) {
            return cached.get();
        }
        try {
            T result = action.get();
            confirm(idempotencyKey, result);
            return result;
        } catch (RuntimeException ex) {
            release(idempotencyKey);
            throw ex;
        }
    }

    private String redisKey(String idempotencyKey) {
        return keyPrefix + idempotencyKey;
    }
}
