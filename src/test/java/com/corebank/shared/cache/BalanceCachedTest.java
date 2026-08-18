package com.corebank.shared.cache;

import com.corebank.query.dto.BalanceDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BalanceCachedTest {

    private static final Long ACCOUNT_ID = 1001L;
    private static final String KEY = "account_balance:1001";
    private static final Duration TTL = Duration.ofSeconds(5);

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private BalanceCached cache;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        cache = new BalanceCached(redis, objectMapper, TTL);
    }

    private static BalanceDTO balance(String total, String available, Long version) {
        return new BalanceDTO(ACCOUNT_ID, new BigDecimal(total), new BigDecimal(available), version);
    }

    private void givenCached(BalanceDTO stored) throws Exception {
        when(valueOps.get(KEY)).thenReturn(objectMapper.writeValueAsString(stored));
    }

    @Test
    void readsAndDeserializesAStoredBalance() throws Exception {
        givenCached(balance("5000.00", "4750.00", 4L));

        Optional<BalanceDTO> found = cache.get(ACCOUNT_ID);

        assertThat(found).contains(balance("5000.00", "4750.00", 4L));
    }

    @Test
    void returnsEmptyWhenTheKeyIsAbsent() {
        when(valueOps.get(KEY)).thenReturn(null);

        assertThat(cache.get(ACCOUNT_ID)).isEmpty();
    }

    @Test
    void degradesToAMissWhenRedisIsUnreachable() {
        when(valueOps.get(KEY)).thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThat(cache.get(ACCOUNT_ID)).isEmpty();
    }

    @Test
    void degradesToAMissWhenTheStoredPayloadIsCorrupt() {
        when(valueOps.get(KEY)).thenReturn("{not-json");

        assertThat(cache.get(ACCOUNT_ID)).isEmpty();
    }

    @Test
    void writesTheBalanceUnderTheAccountKeyWithTheConfiguredTtl() throws Exception {
        BalanceDTO toStore = balance("5000.00", "4750.00", 4L);

        cache.put(toStore);

        verify(valueOps).set(KEY, objectMapper.writeValueAsString(toStore), TTL);
    }

    @Test
    void swallowsWriteFailures() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> cache.put(balance("5000.00", "4750.00", 4L))).doesNotThrowAnyException();
    }

    @Test
    void deletesTheAccountKeyOnEvict() {
        cache.evict(ACCOUNT_ID);

        verify(redis).delete(KEY);
    }

    @Test
    void swallowsEvictFailures() {
        when(redis.delete(KEY)).thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> cache.evict(ACCOUNT_ID)).doesNotThrowAnyException();
    }

    @Test
    void putIfNewerStoresWhenTheCacheIsEmpty() throws Exception {
        when(valueOps.get(KEY)).thenReturn(null);
        BalanceDTO incoming = balance("5000.00", "4750.00", 4L);

        cache.putIfNewer(incoming);

        verify(valueOps).set(eq(KEY), eq(objectMapper.writeValueAsString(incoming)), eq(TTL));
    }

    @Test
    void putIfNewerStoresAHigherVersion() throws Exception {
        givenCached(balance("5000.00", "5000.00", 3L));
        BalanceDTO incoming = balance("5000.00", "4750.00", 4L);

        cache.putIfNewer(incoming);

        verify(valueOps).set(eq(KEY), eq(objectMapper.writeValueAsString(incoming)), eq(TTL));
    }

    @Test
    void putIfNewerDiscardsAnOlderVersion() throws Exception {
        givenCached(balance("5000.00", "4750.00", 9L));

        cache.putIfNewer(balance("5000.00", "5000.00", 4L));

        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void putIfNewerOverwritesOnTheSameVersion() throws Exception {
        givenCached(balance("5000.00", "4750.00", 4L));
        BalanceDTO incoming = balance("5000.00", "4750.00", 4L);

        cache.putIfNewer(incoming);

        verify(valueOps).set(eq(KEY), eq(objectMapper.writeValueAsString(incoming)), eq(TTL));
    }

    @Test
    void putIfNewerStoresWhenTheCachedVersionIsUnknown() throws Exception {
        givenCached(new BalanceDTO(ACCOUNT_ID, new BigDecimal("5000.00"), new BigDecimal("4750.00"), null));
        BalanceDTO incoming = balance("5000.00", "4700.00", 4L);

        cache.putIfNewer(incoming);

        verify(valueOps).set(eq(KEY), eq(objectMapper.writeValueAsString(incoming)), eq(TTL));
    }

    @Test
    void putIfNewerStoresWhenTheIncomingVersionIsUnknown() throws Exception {
        givenCached(balance("5000.00", "4750.00", 9L));
        BalanceDTO incoming = new BalanceDTO(ACCOUNT_ID, new BigDecimal("5000.00"),
                new BigDecimal("4700.00"), null);

        cache.putIfNewer(incoming);

        verify(valueOps).set(eq(KEY), eq(objectMapper.writeValueAsString(incoming)), eq(TTL));
    }
}
