package com.corebank.shared.exception;

public class InvalidAuthorizationStateException extends RuntimeException {
    public InvalidAuthorizationStateException(String authorizationCode, String current, String expected) {
        super("Authorization " + authorizationCode + " is in state " + current + ", expected " + expected);
    }
}
