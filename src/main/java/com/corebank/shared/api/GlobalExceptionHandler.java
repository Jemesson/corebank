package com.corebank.shared.api;

import com.corebank.shared.exception.AccountNotFoundException;
import com.corebank.shared.exception.AuthorizationNotFoundException;
import com.corebank.shared.exception.DuplicateRequestInProgressException;
import com.corebank.shared.exception.InsufficientBalanceException;
import com.corebank.shared.exception.InvalidAmountException;
import com.corebank.shared.exception.InvalidAuthorizationStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ApiError> handleInsufficientBalance(InsufficientBalanceException e) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiError.of("INSUFFICIENT_BALANCE", e.getMessage()));
    }

    @ExceptionHandler({AccountNotFoundException.class, AuthorizationNotFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(InvalidAmountException.class)
    public ResponseEntity<ApiError> handleInvalidAmount(InvalidAmountException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("INVALID_AMOUNT", e.getMessage()));
    }

    @ExceptionHandler(InvalidAuthorizationStateException.class)
    public ResponseEntity<ApiError> handleInvalidState(InvalidAuthorizationStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("INVALID_AUTHORIZATION_STATE", e.getMessage()));
    }

    @ExceptionHandler(DuplicateRequestInProgressException.class)
    public ResponseEntity<ApiError> handleDuplicateInProgress(DuplicateRequestInProgressException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("REQUEST_IN_PROGRESS", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION_ERROR", "INVALID REQUEST", fields));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getAllValidationResults().forEach(result ->
                result.getResolvableErrors().forEach(error -> {
                    String name = (error instanceof FieldError fieldError)
                            ? fieldError.getField()
                            : result.getMethodParameter().getParameterName();
                    fields.putIfAbsent(name, error.getDefaultMessage());
                }));
        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION_ERROR", "INVALID REQUEST", fields));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("MISSING_HEADER", "HEADER IS REQUIRED: " + e.getHeaderName()));
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ApiError> handleDatastoreUnavailable(DataAccessResourceFailureException e) {
        log.error("Banco de dados indisponivel", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("DATASTORE_UNAVAILABLE", "SERVICE UNAVAILABLE: " + e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Erro nao tratado", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "INTERNAL ERROR" + e.getMessage()));
    }
}
