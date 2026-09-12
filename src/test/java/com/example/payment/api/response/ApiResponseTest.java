package com.example.payment.api.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void failureNormalizesNullAndEmptyErrors() {
        ApiResponse<Void> nullErrors = ApiResponse.failure("oops", (List<ApiError>) null, "req_1");
        assertThat(nullErrors.errors()).isEmpty();
        assertThat(nullErrors.message()).isEqualTo("oops");

        ApiResponse<Void> empty = ApiResponse.failure("oops", List.of(), "req_2");
        assertThat(empty.errors()).isEmpty();
    }
}
