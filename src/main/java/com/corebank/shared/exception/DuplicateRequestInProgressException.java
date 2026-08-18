package com.corebank.shared.exception;

public class DuplicateRequestInProgressException extends RuntimeException {
    public DuplicateRequestInProgressException(String idempotencyKey) {
        super("Request with the idempotency key " + idempotencyKey + " is still in progress");
    }
}
