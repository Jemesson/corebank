package com.corebank.shared.exception;

public class AuthorizationNotFoundException extends RuntimeException {
    public AuthorizationNotFoundException(String authorizationCode) {
        super("Authorization " + authorizationCode + " not found");
    }
}
