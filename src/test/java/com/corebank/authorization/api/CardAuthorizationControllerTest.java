package com.corebank.authorization.api;

import com.corebank.authorization.dto.CardAuthorizationResponse;
import com.corebank.authorization.service.CardAuthorizationService;
import com.corebank.helpers.MockMvcs;
import com.corebank.shared.exception.AuthorizationNotFoundException;
import com.corebank.shared.exception.InvalidAuthorizationStateException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
class CardAuthorizationControllerTest {

    private static final String KEY = "idem-card-1";
    private static final String CODE = "AUT0123456789ABCD";

    @Mock private CardAuthorizationService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcs.standalone(new CardAuthorizationController(service));
    }

    private static String body(String accountId, String amount, String merchant) {
        return """
               {"accountId":%s,"amount":%s,"merchant":%s}
               """.formatted(accountId, amount, merchant);
    }

    private org.springframework.test.web.servlet.ResultActions authorize(String key, String json)
            throws Exception {
        var request = post("/api/card/authorization")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    @Test
    void returns200WithTheApprovedAuthorization() throws Exception {
        when(service.authorize(KEY, 1001L, new BigDecimal("250.00"), "PADARIA"))
                .thenReturn(new CardAuthorizationResponse(CODE, "APPROVED", null, new BigDecimal("4750.00")));

        authorize(KEY, body("1001", "250.00", "\"PADARIA\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationCode").value(CODE))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.availableBalance").value(4750.00));
    }

    @Test
    void returns200WithDeniedWhenTheBalanceIsShort() throws Exception {
        when(service.authorize(anyString(), anyLong(), any(), anyString()))
                .thenReturn(new CardAuthorizationResponse(CODE, "DENIED",
                        "Insufficient available balance", new BigDecimal("100.00")));

        authorize(KEY, body("1001", "250.00", "\"PADARIA\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DENIED"))
                .andExpect(jsonPath("$.reason").value("Insufficient available balance"));
    }

    @Test
    void returns400WhenTheIdempotencyKeyHeaderIsMissing() throws Exception {
        authorize(null, body("1001", "250.00", "\"PADARIA\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_HEADER"));

        verify(service, never()).authorize(anyString(), anyLong(), any(), anyString());
    }

    @Test
    void returns400AndNamesEveryMissingFieldAtOnce() throws Exception {
        authorize(KEY, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.accountId").value("accountId is required"))
                .andExpect(jsonPath("$.fields.amount").value("amount is required"))
                .andExpect(jsonPath("$.fields.merchant").value("merchant is required"));
    }

    @Test
    void returns400ForANonPositiveAmount() throws Exception {
        authorize(KEY, body("1001", "0.00", "\"PADARIA\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.amount").value("amount must be greater than zero"));
    }

    @Test
    void returns400ForMoreThanTwoDecimalPlaces() throws Exception {
        authorize(KEY, body("1001", "250.001", "\"PADARIA\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.amount").value("amount accepts at most 2 decimal places"));
    }

    @Test
    void returns200WhenTheCaptureSucceeds() throws Exception {
        when(service.capture(CODE)).thenReturn(
                new CardAuthorizationResponse(CODE, "CAPTURED", null, new BigDecimal("4750.00")));

        mockMvc.perform(post("/api/card/authorization/{code}/capture", CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    void returns200WhenTheReversalSucceeds() throws Exception {
        when(service.reverse(CODE)).thenReturn(
                new CardAuthorizationResponse(CODE, "REVERSED", null, new BigDecimal("5000.00")));

        mockMvc.perform(post("/api/card/authorization/{code}/reversal", CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));
    }

    @Test
    void returns404WhenCapturingAnUnknownAuthorization() throws Exception {
        when(service.capture(CODE)).thenThrow(new AuthorizationNotFoundException(CODE));

        mockMvc.perform(post("/api/card/authorization/{code}/capture", CODE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void returns409WhenCapturingAnAuthorizationThatIsNotApproved() throws Exception {
        when(service.capture(CODE))
                .thenThrow(new InvalidAuthorizationStateException(CODE, "CAPTURED", "APPROVED"));

        mockMvc.perform(post("/api/card/authorization/{code}/capture", CODE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_AUTHORIZATION_STATE"));
    }

    @Test
    void returns409WhenReversingAnAuthorizationThatIsNotApproved() throws Exception {
        when(service.reverse(CODE))
                .thenThrow(new InvalidAuthorizationStateException(CODE, "REVERSED", "APPROVED"));

        mockMvc.perform(post("/api/card/authorization/{code}/reversal", CODE))
                .andExpect(status().isConflict());
    }
}
