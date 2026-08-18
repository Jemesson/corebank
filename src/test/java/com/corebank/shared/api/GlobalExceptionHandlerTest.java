package com.corebank.shared.api;

import com.corebank.shared.exception.AccountNotFoundException;
import com.corebank.shared.exception.AuthorizationNotFoundException;
import com.corebank.shared.exception.DuplicateRequestInProgressException;
import com.corebank.shared.exception.InsufficientBalanceException;
import com.corebank.shared.exception.InvalidAmountException;
import com.corebank.shared.exception.InvalidAuthorizationStateException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static void assertError(ResponseEntity<ApiError> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo(code);
        assertThat(response.getBody().timestamp()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void mapsInsufficientBalanceTo422() {
        var response = handler.handleInsufficientBalance(
                new InsufficientBalanceException(1001L, new BigDecimal("10.00"), new BigDecimal("100.00")));

        assertError(response, HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE");
        assertThat(response.getBody().message()).contains("available=10.00", "requested=100.00");
    }

    @Test
    void mapsAMissingAccountTo404() {
        var response = handler.handleNotFound(new AccountNotFoundException(1001L));

        assertError(response, HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("Account 1001 not found");
    }

    @Test
    void mapsAMissingAuthorizationTo404() {
        var response = handler.handleNotFound(new AuthorizationNotFoundException("AUT1"));

        assertError(response, HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("Authorization AUT1 not found");
    }

    @Test
    void mapsAnInvalidAmountTo400() {
        var response = handler.handleInvalidAmount(new InvalidAmountException("Valor deve ser maior que zero"));

        assertError(response, HttpStatus.BAD_REQUEST, "INVALID_AMOUNT");
        assertThat(response.getBody().message()).isEqualTo("Valor deve ser maior que zero");
    }

    @Test
    void mapsAnInvalidAuthorizationStateTo409() {
        var response = handler.handleInvalidState(
                new InvalidAuthorizationStateException("AUT1", "CAPTURED", "APPROVED"));

        assertError(response, HttpStatus.CONFLICT, "INVALID_AUTHORIZATION_STATE");
        assertThat(response.getBody().message())
                .isEqualTo("Authorization AUT1 is in state CAPTURED, expected APPROVED");
    }

    @Test
    void mapsAnInFlightDuplicateTo409() {
        var response = handler.handleDuplicateInProgress(new DuplicateRequestInProgressException("key-1"));

        assertError(response, HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS");
    }

    @Test
    void mapsAMissingHeaderTo400() throws Exception {
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("headerStub", String.class), 0);

        var response = handler.handleMissingHeader(
                new MissingRequestHeaderException("Idempotency-Key", parameter));

        assertError(response, HttpStatus.BAD_REQUEST, "MISSING_HEADER");
        assertThat(response.getBody().message()).isEqualTo("HEADER IS REQUIRED: Idempotency-Key");
    }

    @Test
    void mapsAnUnreachableDatastoreTo503() {
        var response = handler.handleDatastoreUnavailable(
                new DataAccessResourceFailureException("primary down"));

        assertError(response, HttpStatus.SERVICE_UNAVAILABLE, "DATASTORE_UNAVAILABLE");
        assertThat(response.getBody().message()).contains("primary down");
    }

    @Test
    void mapsAnythingElseTo500() {
        var response = handler.handleUnexpected(new IllegalStateException("boom"));

        assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
    }

    @Test
    void mapsBodyValidationErrorsTo400ListingEveryField() throws Exception {
        var response = handler.handleValidation(methodArgumentNotValid(
                new FieldError("request", "value", "value is required"),
                new FieldError("request", "targetPix", "targetPix is required")));

        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(response.getBody().message()).isEqualTo("INVALID REQUEST");
        assertThat(response.getBody().fields())
                .containsEntry("value", "value is required")
                .containsEntry("targetPix", "targetPix is required");
    }

    @Test
    void keepsTheFirstMessageWhenAFieldBreaksSeveralConstraints() throws Exception {
        var response = handler.handleValidation(methodArgumentNotValid(
                new FieldError("request", "value", "value must be greater than zero"),
                new FieldError("request", "value", "value accepts at most 2 decimal places")));

        assertThat(response.getBody().fields())
                .containsExactly(entry("value", "value must be greater than zero"));
    }

    private MethodArgumentNotValidException methodArgumentNotValid(FieldError... errors) throws Exception {
        BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        for (FieldError error : errors) {
            bindingResult.addError(error);
        }
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("headerStub", String.class), 0);
        return new MethodArgumentNotValidException(parameter, bindingResult);
    }

    @Test
    void errorBodyOmitsTheFieldMapWhenThereAreNoFieldErrors() {
        ApiError error = ApiError.of("NOT_FOUND", "Account 1001 not found");

        assertThat(error.fields()).isNull();
        assertThat(error.timestamp()).isNotNull();
    }

    @Test
    void errorBodyCarriesTheFieldMapWhenValidationFails() {
        ApiError error = ApiError.of("VALIDATION_ERROR", "INVALID REQUEST",
                Map.of("value", "value is required"));

        assertThat(error.fields()).containsEntry("value", "value is required");
    }

    @SuppressWarnings("unused")
    private void headerStub(String idempotencyKey) {}
}
