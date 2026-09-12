package com.example.payment.api.exception;

import com.example.payment.api.response.ApiError;
import com.example.payment.api.response.ApiResponse;
import com.example.payment.common.exception.DuplicateEmailException;
import com.example.payment.common.exception.IdempotencyConflictException;
import com.example.payment.common.exception.InvalidRequestException;
import com.example.payment.common.exception.ServiceUnavailableException;
import com.example.payment.common.exception.TransactionNotFoundException;
import com.example.payment.common.exception.UserNotFoundException;
import com.example.payment.common.logging.LogFactory;
import com.example.payment.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LogFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleUserNotFound(
            UserNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure(
                        "User not found",
                        ApiError.of("USER_NOT_FOUND", ex.getMessage()),
                        RequestIdFilter.resolve(request)));
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleTransactionNotFound(
            TransactionNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure(
                        "Transaction not found",
                        ApiError.of("NOT_FOUND", ex.getMessage()),
                        RequestIdFilter.resolve(request)));
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateEmail(
            DuplicateEmailException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure(
                        "Email already registered",
                        ApiError.of("EMAIL_ALREADY_EXISTS", "email", ex.getMessage()),
                        RequestIdFilter.resolve(request)));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleIdempotencyConflict(
            IdempotencyConflictException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure(
                        "Idempotency conflict",
                        ApiError.of("IDEMPOTENCY_CONFLICT", ex.getMessage()),
                        RequestIdFilter.resolve(request)));
    }

    @ExceptionHandler({
            ServiceUnavailableException.class,
            DataAccessException.class,
            TransactionException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleServiceUnavailable(
            Exception ex, HttpServletRequest request) {
        log.warn("Service unavailable: {}", ex.toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.failure(
                        "Service temporarily unavailable",
                        ApiError.of("SERVICE_UNAVAILABLE", "Unable to persist transaction"),
                        RequestIdFilter.resolve(request)));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(
            InvalidRequestException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(
                        "Validation failed",
                        ApiError.of(ex.getCode(), ex.getField(), ex.getMessage()),
                        RequestIdFilter.resolve(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toValidationError)
                .toList();
        if (errors.isEmpty()) {
            errors = List.of(ApiError.of("VALIDATION_ERROR", "Validation failed"));
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("Validation failed", errors, RequestIdFilter.resolve(request)));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiError> errors = ex.getConstraintViolations().stream()
                .map(this::toConstraintError)
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("Validation failed", errors, RequestIdFilter.resolve(request)));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex, HttpServletRequest request) {
        String field = null;
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            field = mismatch.getName();
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(
                        "Invalid request",
                        ApiError.of("VALIDATION_ERROR", field, "Request could not be parsed or has an invalid type"),
                        RequestIdFilter.resolve(request)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(
            NoResourceFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure(
                        "Not found",
                        ApiError.of("NOT_FOUND", "No endpoint matches this path"),
                        RequestIdFilter.resolve(request)));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.failure(
                        "Method not allowed",
                        ApiError.of("METHOD_NOT_ALLOWED", ex.getMessage()),
                        RequestIdFilter.resolve(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure(
                        "Unexpected error",
                        ApiError.of("INTERNAL_ERROR", "An unexpected error occurred"),
                        RequestIdFilter.resolve(request)));
    }

    private ApiError toValidationError(FieldError fieldError) {
        return ApiError.of(
                "VALIDATION_ERROR",
                fieldError.getField(),
                fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "invalid");
    }

    private ApiError toConstraintError(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath() != null ? violation.getPropertyPath().toString() : null;
        return ApiError.of("VALIDATION_ERROR", path, violation.getMessage());
    }
}
