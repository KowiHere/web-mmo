package com.kowihere.mmo.account;

import org.springframework.http.HttpStatus;

/**
 * A failure the caller is meant to read. The message is shown to the player, so
 * it must say what to do about it - and must never say more than the caller is
 * entitled to know.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    public HttpStatus status() {
        return status;
    }
}
