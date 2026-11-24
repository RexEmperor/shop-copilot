package com.shopcopilot.business.common;

import java.time.Instant;
import org.springframework.http.HttpStatus;

public record Result<T>(int code, String message, T data, Instant timestamp) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(HttpStatus.OK.value(), "success", data, Instant.now());
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> error(HttpStatus status, String message) {
        return new Result<>(status.value(), message, null, Instant.now());
    }
}
