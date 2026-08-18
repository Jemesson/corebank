package com.corebank.shared.idempotency;

import com.corebank.shared.exception.DuplicateRequestInProgressException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyManagementTest {

    private static final String KEY = "idem-key-1";
    private static final String ENDPOINT = "/api/pix/payment";
    private static final Long ACCOUNT_ID = 1001L;

    @Mock private JdbcTemplate jdbcTemplate;

    @InjectMocks private IdempotencyManagement idempotency;

    private void givenInsertAffects(int rows) {
        when(jdbcTemplate.update(anyString(), eq(KEY), eq(ENDPOINT), eq(ACCOUNT_ID))).thenReturn(rows);
    }

    private void givenStoredResponse(String... responses) {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(KEY), eq(ENDPOINT)))
                .thenReturn(Arrays.asList(responses));
    }

    @Test
    void reservesTheKeyOnTheFirstAttempt() {
        givenInsertAffects(1);

        assertThat(idempotency.reserveOrReplay(KEY, ENDPOINT, ACCOUNT_ID)).isEmpty();

        verify(jdbcTemplate, never()).queryForList(anyString(), any(Class.class), any(), any());
    }

    @Test
    void insertsTheReservationAsInProgress() {
        givenInsertAffects(1);

        idempotency.reserveOrReplay(KEY, ENDPOINT, ACCOUNT_ID);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq(KEY), eq(ENDPOINT), eq(ACCOUNT_ID));
        assertThat(sql.getValue())
                .contains("INSERT INTO idempotency_keys")
                .contains("'IN_PROGRESS'")
                .contains("ON CONFLICT (key, endpoint) DO NOTHING");
    }

    @Test
    void replaysTheStoredResponseWhenTheKeyWasAlreadyCompleted() {
        givenInsertAffects(0);
        givenStoredResponse("E123-original");

        Optional<String> replay = idempotency.reserveOrReplay(KEY, ENDPOINT, ACCOUNT_ID);

        assertThat(replay).contains("E123-original");
    }

    @Test
    void failsWhenAConcurrentRequestHoldsTheKeyWithoutAResponseYet() {
        givenInsertAffects(0);
        givenStoredResponse((String) null);

        assertThatThrownBy(() -> idempotency.reserveOrReplay(KEY, ENDPOINT, ACCOUNT_ID))
                .isInstanceOf(DuplicateRequestInProgressException.class)
                .hasMessage("Request with the idempotency key " + KEY + " is still in progress");
    }

    @Test
    void failsWhenTheConflictingRowVanishedBeforeItCouldBeRead() {
        givenInsertAffects(0);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(KEY), eq(ENDPOINT)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> idempotency.reserveOrReplay(KEY, ENDPOINT, ACCOUNT_ID))
                .isInstanceOf(DuplicateRequestInProgressException.class);
    }

    @Test
    void scopesTheReplayLookupToTheKeyAndEndpointPair() {
        givenInsertAffects(0);
        givenStoredResponse("E123-original");

        idempotency.reserveOrReplay(KEY, ENDPOINT, ACCOUNT_ID);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), eq(String.class), eq(KEY), eq(ENDPOINT));
        assertThat(sql.getValue()).contains("WHERE key = ? AND endpoint = ?");
    }

    @Test
    void completeStoresTheResponseReferenceAndFlipsTheStatus() {
        idempotency.complete(KEY, ENDPOINT, "E123-final");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq("E123-final"), eq(KEY), eq(ENDPOINT));
        assertThat(sql.getValue())
                .contains("UPDATE idempotency_keys")
                .contains("SET status = 'COMPLETED', response_ref = ?")
                .contains("WHERE key = ? AND endpoint = ?");
    }
}
