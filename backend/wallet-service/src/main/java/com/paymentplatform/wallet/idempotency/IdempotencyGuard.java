package com.paymentplatform.wallet.idempotency;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Redis-backed safe-retry guard for write endpoints (design doc 6.2.3): a client-supplied
 * {@code Idempotency-Key} identifies one logical attempt at an operation, so a network-retried
 * or double-tapped request never runs the underlying mutation twice.
 *
 * Mechanics: an atomic Redis {@code SETNX} reserves the key as {@code IN_PROGRESS} (24h TTL by
 * default). The first caller to win that race runs the real action and, on success, overwrites
 * the key with the serialized result - any later caller with the same key gets that cached
 * result back instead of re-running the action. A caller that arrives while the first is still
 * mid-flight gets {@link IdempotencyKeyInProgressException} (-&gt; 409): told to poll, not to
 * resubmit.
 *
 * <b>Deliberate simplification vs. the design doc's literal wording</b>: only a *successful*
 * completion is cached. If the action throws, the key is released (deleted), not cached with
 * the failure - so a genuinely retried request after a transient failure (a lost-connection
 * retry, an optimistic-lock conflict) gets a fresh attempt instead of being permanently stuck
 * replaying an old error. This never risks a double mutation: nothing succeeded on the failed
 * attempt, so there's nothing to double up on a retry.
 */
@Component
public class IdempotencyGuard {

    private static final String IN_PROGRESS_MARKER = "IN_PROGRESS";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final String keyPrefix;

    public IdempotencyGuard(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                             @Value("${wallet.idempotency.ttl-hours}") long ttlHours,
                             @Value("${wallet.idempotency.key-prefix}") String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofHours(ttlHours);
        this.keyPrefix = keyPrefix;
    }

    /**
     * Reserves {@code idempotencyKey} for a fresh attempt, or returns the previously-cached
     * result if this key already completed once. Throws {@link IdempotencyKeyInProgressException}
     * if another attempt with this key is currently running.
     *
     * @return empty if the caller won the reservation and should proceed (and later call
     *         {@link #confirm}); present with the cached result if this key already succeeded once
     */
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

    /** Caches {@code response} as this key's final result, replacing the IN_PROGRESS marker. */
    public void confirm(String idempotencyKey, Object response) {
        redisTemplate.opsForValue().set(redisKey(idempotencyKey), objectMapper.writeValueAsString(response), ttl);
    }

    /** Frees the key entirely (e.g. after a failed attempt) so a retry starts fresh. */
    public void release(String idempotencyKey) {
        redisTemplate.delete(redisKey(idempotencyKey));
    }

    /**
     * Convenience wrapper around checkAndReserve/confirm/release for the common case: run
     * {@code action} at most once per key, cache its result on success, release the key on
     * failure.
     */
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
