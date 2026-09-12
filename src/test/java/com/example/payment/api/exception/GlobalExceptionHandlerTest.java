package com.example.payment.api.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.payment.api.response.ApiResponse;
import com.example.payment.common.exception.InvalidRequestException;
import com.example.payment.common.exception.ServiceUnavailableException;
import com.example.payment.common.web.RequestIdFilter;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void handlesServiceUnavailableAndUnexpected() {
        request.setAttribute(RequestIdFilter.ATTR, "req_t");

        ResponseEntity<ApiResponse<Void>> unavailable =
                handler.handleServiceUnavailable(new ServiceUnavailableException("down"), request);
        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        ResponseEntity<ApiResponse<Void>> unexpected =
                handler.handleUnexpected(new RuntimeException("boom"), request);
        assertThat(unexpected.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(unexpected.getBody().errors().get(0).code()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void handlesValidationWithEmptyFieldErrorsAndConstraintViolations() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "obj");
        MethodParameter param = mock(MethodParameter.class);
        MethodArgumentNotValidException empty =
                new MethodArgumentNotValidException(param, binding);
        ResponseEntity<ApiResponse<Void>> emptyResp = handler.handleValidation(empty, request);
        assertThat(emptyResp.getBody().errors()).hasSize(1);

        binding.addError(new FieldError("obj", "email", null, false, null, null, null));
        MethodArgumentNotValidException withNullMsg =
                new MethodArgumentNotValidException(param, binding);
        ResponseEntity<ApiResponse<Void>> nullMsg = handler.handleValidation(withNullMsg, request);
        assertThat(nullMsg.getBody().errors().get(0).message()).isEqualTo("invalid");

        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("amount");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must be positive");
        ResponseEntity<ApiResponse<Void>> constraints = handler.handleConstraintViolation(
                new ConstraintViolationException(Set.of(violation)), request);
        assertThat(constraints.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(constraints.getBody().errors().get(0).field()).isEqualTo("amount");
    }

    @Test
    void handlesBadRequestVariantsAndNotFoundAndMethodNotAllowed() {
        ResponseEntity<ApiResponse<Void>> unreadable = handler.handleBadRequest(
                new HttpMessageNotReadableException("bad", mock(HttpInputMessage.class)), request);
        assertThat(unreadable.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        MethodArgumentTypeMismatchException mismatch =
                new MethodArgumentTypeMismatchException("x", String.class, "id", null, null);
        ResponseEntity<ApiResponse<Void>> typed = handler.handleBadRequest(mismatch, request);
        assertThat(typed.getBody().errors().get(0).field()).isEqualTo("id");

        ResponseEntity<ApiResponse<Void>> missing = handler.handleNoResource(
                new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/api/nope"),
                request);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<ApiResponse<Void>> method = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("DELETE"), request);
        assertThat(method.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void handlesInvalidRequest() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleInvalidRequest(
                new InvalidRequestException("cursor", "cursor is invalid"), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errors().get(0).field()).isEqualTo("cursor");
    }
}
