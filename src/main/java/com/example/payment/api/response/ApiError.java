package com.example.payment.api.response;

public record ApiError(
        String code,
        String field,
        String message
) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, null, message);
    }

    public static ApiError of(String code, String field, String message) {
        return new ApiError(code, field, message);
    }
}
