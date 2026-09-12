package com.example.payment.api.response;

import java.util.Collections;
import java.util.List;

public record ApiResponse<T>(
        T data,
        String message,
        List<ApiError> errors,
        ApiMeta meta
) {

    public static <T> ApiResponse<T> ok(T data, String message, String requestId) {
        return new ApiResponse<>(data, message, List.of(), new ApiMeta(requestId));
    }

    public static <T> ApiResponse<T> created(T data, String message, String requestId) {
        return ok(data, message, requestId);
    }

    public static <T> ApiResponse<T> failure(String message, List<ApiError> errors, String requestId) {
        List<ApiError> safe = errors == null ? List.of() : List.copyOf(errors);
        return new ApiResponse<>(null, message, safe.isEmpty() ? List.of() : safe, new ApiMeta(requestId));
    }

    public static <T> ApiResponse<T> failure(String message, ApiError error, String requestId) {
        return failure(message, Collections.singletonList(error), requestId);
    }
}
