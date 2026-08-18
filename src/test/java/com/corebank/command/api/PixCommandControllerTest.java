package com.corebank.command.api;

import com.corebank.command.dto.PixPaymentResponse;
import com.corebank.command.service.PixCommandService;
import com.corebank.helpers.MockMvcs;
import com.corebank.shared.exception.AccountNotFoundException;
import com.corebank.shared.exception.DuplicateRequestInProgressException;
import com.corebank.shared.exception.InsufficientBalanceException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PixCommandControllerTest {

    private static final String KEY = "idem-key-1";

    @Mock private PixCommandService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcs.standalone(new PixCommandController(service));
    }

    private static String body(String originAccountId, String targetPix, String value) {
        return """
               {"originAccountId":%s,"targetPix":%s,"value":%s}
               """.formatted(originAccountId, targetPix, value);
    }

    private org.springframework.test.web.servlet.ResultActions payment(String key, String json) throws Exception {
        var request = post("/api/pix/payment")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    @Test
    void returns200WithTheEndToEndIdOnSuccess() throws Exception {
        when(service.payment(KEY, 1001L, "target@pix.com", new BigDecimal("100.00")))
                .thenReturn(PixPaymentResponse.completed("E123"));

        payment(KEY, body("1001", "\"target@pix.com\"", "100.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endToEndId").value("E123"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void returns200WithReplayedForARepeatedKey() throws Exception {
        when(service.payment(anyString(), anyLong(), anyString(), any()))
                .thenReturn(PixPaymentResponse.replayed("E123"));

        payment(KEY, body("1001", "\"target@pix.com\"", "100.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLAYED"));
    }

    @Test
    void returns400WhenTheIdempotencyKeyHeaderIsMissing() throws Exception {
        payment(null, body("1001", "\"target@pix.com\"", "100.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_HEADER"))
                .andExpect(jsonPath("$.message").value("HEADER IS REQUIRED: Idempotency-Key"));

        verify(service, never()).payment(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void returns400WhenTheIdempotencyKeyHeaderIsBlank() throws Exception {
        payment("   ", body("1001", "\"target@pix.com\"", "100.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        verify(service, never()).payment(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void returns400AndNamesEveryMissingFieldAtOnce() throws Exception {
        payment(KEY, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.originAccountId").value("originAccountId is required"))
                .andExpect(jsonPath("$.fields.targetPix").value("targetPix is required"))
                .andExpect(jsonPath("$.fields.value").value("value is required"));

        verify(service, never()).payment(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void returns400ForANonPositiveValue() throws Exception {
        payment(KEY, body("1001", "\"target@pix.com\"", "0.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.value").value("value must be greater than zero"));
    }

    @Test
    void returns400ForMoreThanTwoDecimalPlaces() throws Exception {
        payment(KEY, body("1001", "\"target@pix.com\"", "10.001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.value").value("value accepts at most 2 decimal places"));
    }

    @Test
    void returns400ForABlankTargetPixKey() throws Exception {
        payment(KEY, body("1001", "\"   \"", "100.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.targetPix").value("targetPix is required"));
    }

    @Test
    void returns404WhenTheAccountDoesNotExist() throws Exception {
        when(service.payment(anyString(), anyLong(), anyString(), any()))
                .thenThrow(new AccountNotFoundException(1001L));

        payment(KEY, body("1001", "\"target@pix.com\"", "100.00"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void returns422WhenTheBalanceIsInsufficient() throws Exception {
        when(service.payment(anyString(), anyLong(), anyString(), any()))
                .thenThrow(new InsufficientBalanceException(1001L,
                        new BigDecimal("10.00"), new BigDecimal("100.00")));

        payment(KEY, body("1001", "\"target@pix.com\"", "100.00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    void returns409WhileTheSameKeyIsStillInFlight() throws Exception {
        when(service.payment(anyString(), anyLong(), anyString(), any()))
                .thenThrow(new DuplicateRequestInProgressException(KEY));

        payment(KEY, body("1001", "\"target@pix.com\"", "100.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_IN_PROGRESS"));
    }

    @Test
    void returns503WhenThePrimaryDatabaseIsDown() throws Exception {
        when(service.payment(anyString(), anyLong(), anyString(), any()))
                .thenThrow(new DataAccessResourceFailureException("primary down"));

        payment(KEY, body("1001", "\"target@pix.com\"", "100.00"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("DATASTORE_UNAVAILABLE"));
    }
}
