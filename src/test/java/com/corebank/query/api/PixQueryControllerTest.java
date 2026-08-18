package com.corebank.query.api;

import com.corebank.helpers.MockMvcs;
import com.corebank.query.dto.BalanceDTO;
import com.corebank.query.dto.PixStatementReadDTO;
import com.corebank.query.service.PixQueryService;
import com.corebank.shared.exception.AccountNotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PixQueryControllerTest {

    private static final Long ACCOUNT_ID = 1001L;

    @Mock private PixQueryService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcs.standalone(new PixQueryController(service));
    }

    @Test
    void returnsTheBalance() throws Exception {
        when(service.getBalance(ACCOUNT_ID)).thenReturn(new BalanceDTO(ACCOUNT_ID,
                new BigDecimal("5000.00"), new BigDecimal("4750.00"), 4L));

        mockMvc.perform(get("/api/pix/{accountId}/balance", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(1001))
                .andExpect(jsonPath("$.totalBalance").value(5000.00))
                .andExpect(jsonPath("$.balance").value(4750.00))
                .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    void returns404ForAnUnknownAccount() throws Exception {
        when(service.getBalance(ACCOUNT_ID)).thenThrow(new AccountNotFoundException(ACCOUNT_ID));

        mockMvc.perform(get("/api/pix/{accountId}/balance", ACCOUNT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void returnsTheStatement() throws Exception {
        when(service.getPixStatement(ACCOUNT_ID, 20, 0)).thenReturn(List.of(
                new PixStatementReadDTO("E123", "target@pix.com", new BigDecimal("100.00"),
                        "SENT", "COMPLETED", Instant.parse("2026-08-18T10:00:00Z"))));

        mockMvc.perform(get("/api/pix/{accountId}/statement", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].endToEndId").value("E123"))
                .andExpect(jsonPath("$[0].targetPixKey").value("target@pix.com"))
                .andExpect(jsonPath("$[0].operationType").value("SENT"));
    }

    @Test
    void defaultsToTwentyEntriesFromTheStart() throws Exception {
        when(service.getPixStatement(ACCOUNT_ID, 20, 0)).thenReturn(List.of());

        mockMvc.perform(get("/api/pix/{accountId}/statement", ACCOUNT_ID))
                .andExpect(status().isOk());

        verify(service).getPixStatement(ACCOUNT_ID, 20, 0);
    }

    @Test
    void honoursExplicitPagination() throws Exception {
        when(service.getPixStatement(ACCOUNT_ID, 5, 10)).thenReturn(List.of());

        mockMvc.perform(get("/api/pix/{accountId}/statement", ACCOUNT_ID)
                        .param("limit", "5")
                        .param("offset", "10"))
                .andExpect(status().isOk());

        verify(service).getPixStatement(ACCOUNT_ID, 5, 10);
    }

    @Test
    void returns500WhenTheQueryPathFailsUnexpectedly() throws Exception {
        when(service.getBalance(ACCOUNT_ID)).thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(get("/api/pix/{accountId}/balance", ACCOUNT_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"));
    }
}
