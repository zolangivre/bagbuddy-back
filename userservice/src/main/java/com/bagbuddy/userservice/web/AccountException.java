package com.bagbuddy.userservice.web;

import org.springframework.http.HttpStatus;

/**
 * Account lifecycle failure the front is expected to render as a field-level message.
 *
 * The {@code code} is the contract: the front matches on it rather than on the wording, so
 * the message can change (or be translated) without breaking the screens.
 */
public class AccountException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AccountException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
