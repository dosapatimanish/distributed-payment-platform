package com.paymentplatform.orchestrator.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for conversion-orchestrator's IdempotencyGuard - identical structure to
 * wallet-service's and fx-rate-service's own IdempotencyGuardTest (see testing-guide.md),
 * since all three classes are deliberate copies of each other.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyGuardTest {

    private static final String KEY_PREFIX = "orchestrator:idem:";
    private static final long TTL_HOURS = 24;

    private record SampleResponse(String id, int value) {
    }

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private IdempotencyGuard guard;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        guard = new IdempotencyGuard(redisTemplate, new ObjectMapper(), TTL_HOURS, KEY_PREFIX);
    }

    @Test
    void checkAndReserve_freshKey_reservesAndReturnsEmpty() {
        when(valueOperations.setIfAbsent(eq(KEY_PREFIX + "key-1"), eq("IN_PROGRESS"), any(Duration.class)))
                .thenReturn(true);

        assertThat(guard.checkAndReserve("key-1", SampleResponse.class)).isEmpty();
        verify(valueOperations, never()).get(any());
    }

    @Test
    void checkAndReserve_completedKey_returnsDeserializedCachedResult() {
        when(valueOperations.setIfAbsent(eq(KEY_PREFIX + "key-1"), eq("IN_PROGRESS"), any(Duration.class)))
                .thenReturn(false);
        when(valueOperations.get(KEY_PREFIX + "key-1")).thenReturn("""
                {"id":"abc","value":42}
                """);

        assertThat(guard.checkAndReserve("key-1", SampleResponse.class))
                .contains(new SampleResponse("abc", 42));
    }

    @Test
    void checkAndReserve_inProgressKey_throws() {
        when(valueOperations.setIfAbsent(eq(KEY_PREFIX + "key-1"), eq("IN_PROGRESS"), any(Duration.class)))
                .thenReturn(false);
        when(valueOperations.get(KEY_PREFIX + "key-1")).thenReturn("IN_PROGRESS");

        assertThatThrownBy(() -> guard.checkAndReserve("key-1", SampleResponse.class))
                .isInstanceOf(IdempotencyKeyInProgressException.class);
    }

    @Test
    void confirm_writesSerializedResultWithConfiguredTtl() {
        guard.confirm("key-1", new SampleResponse("abc", 42));

        verify(valueOperations).set(eq(KEY_PREFIX + "key-1"), eq("""
                {"id":"abc","value":42}"""), eq(Duration.ofHours(TTL_HOURS)));
    }

    @Test
    void release_deletesTheKey() {
        guard.release("key-1");

        verify(redisTemplate).delete(KEY_PREFIX + "key-1");
    }

    @Test
    void runIdempotent_freshKey_runsActionOnceAndConfirms() {
        when(valueOperations.setIfAbsent(eq(KEY_PREFIX + "key-1"), eq("IN_PROGRESS"), any(Duration.class)))
                .thenReturn(true);

        SampleResponse result = guard.runIdempotent("key-1", SampleResponse.class,
                () -> new SampleResponse("abc", 42));

        assertThat(result).isEqualTo(new SampleResponse("abc", 42));
        verify(valueOperations).set(eq(KEY_PREFIX + "key-1"), any(String.class), any(Duration.class));
    }

    @Test
    void runIdempotent_actionThrows_releasesKeyAndRethrows() {
        when(valueOperations.setIfAbsent(eq(KEY_PREFIX + "key-1"), eq("IN_PROGRESS"), any(Duration.class)))
                .thenReturn(true);

        assertThatThrownBy(() -> guard.runIdempotent("key-1", SampleResponse.class, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        verify(redisTemplate).delete(KEY_PREFIX + "key-1");
    }
}
