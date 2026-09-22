package com.bagbuddy.tripservice.web;

/**
 * A refusal the front is expected to explain, carried as a BAD_REQUEST with a stable
 * {@code extensions.code}. The code is the contract: screens match on it, never on the message.
 */
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
