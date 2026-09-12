package com.example.payment.api.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.payment.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

class RateLimitFilterTest {

    private RateLimitProperties properties;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.getUser().setCapacity(2);
        properties.getUser().setWindowSeconds(60);
        properties.getMerchant().setCapacity(2);
        properties.getMerchant().setWindowSeconds(60);
        properties.getIp().setCapacity(100);
        properties.getIp().setWindowSeconds(60);
        filter = new RateLimitFilter(properties, new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    void skipsWhenDisabledOrNonApi() throws Exception {
        properties.setEnabled(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/x");
        assertThat(filter.shouldNotFilter(request)).isTrue();

        properties.setEnabled(true);
        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/actuator/health");
        assertThat(filter.shouldNotFilter(health)).isTrue();

        MockHttpServletRequest nullPath = new MockHttpServletRequest();
        nullPath.setRequestURI(null);
        assertThat(filter.shouldNotFilter(nullPath)).isTrue();
    }

    @Test
    void emptyBodyAndMissingContentTypeSkipJsonExtract() throws Exception {
        MockHttpServletRequest empty = new MockHttpServletRequest("POST", "/api/v1/transactions");
        empty.setContentType("application/json");
        empty.setContent(new byte[0]);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(empty, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());

        MockHttpServletRequest noCt = new MockHttpServletRequest("POST", "/api/v1/transactions");
        noCt.setContent("{}".getBytes(StandardCharsets.UTF_8));
        FilterChain chain2 = mock(FilterChain.class);
        filter.doFilter(noCt, new MockHttpServletResponse(), chain2);
        verify(chain2).doFilter(any(), any());
    }

    @Test
    void ignoresNonValueJsonFieldsAndNullRemoteAddr() throws Exception {
        String body = "{\"userId\":{\"nested\":true},\"merchantId\":null,\"amount\":1}";
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(jsonRequest("POST", "/api/v1/transactions", body), new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");
        request.setRemoteAddr(null);
        FilterChain chain2 = mock(FilterChain.class);
        filter.doFilter(request, new MockHttpServletResponse(), chain2);
        verify(chain2).doFilter(any(), any());
    }

    @Test
    void extractsUserFromPathAndAllowsGet() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/11111111-1111-1111-1111-111111111111");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsWhenUserBucketExhausted() throws Exception {
        String body = "{\"userId\":\"11111111-1111-1111-1111-111111111111\",\"merchantId\":\"mch\",\"amount\":1,\"category\":\"GROCERIES\"}";
        for (int i = 0; i < 2; i++) {
            assertAllowed(postJson(body));
        }
        MockHttpServletResponse rejected = postJson(body);
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isNotBlank();
        assertThat(rejected.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
    }

    @Test
    void rejectsWhenMerchantBucketExhausted() throws Exception {
        properties.getUser().setCapacity(100);
        properties.getMerchant().setCapacity(1);
        String body = "{\"userId\":\"22222222-2222-2222-2222-222222222222\",\"merchantId\":\"mch_only\",\"amount\":1,\"category\":\"GROCERIES\"}";
        assertAllowed(postJson(body));
        MockHttpServletResponse rejected = postJson(body);
        assertThat(rejected.getStatus()).isEqualTo(429);
    }

    @Test
    void rejectsWhenIpBucketExhaustedUsingForwardedFor() throws Exception {
        properties.getUser().setCapacity(100);
        properties.getMerchant().setCapacity(100);
        properties.getIp().setCapacity(1);
        String body = "{\"amount\":1,\"category\":\"GROCERIES\"}";
        MockHttpServletRequest first = jsonRequest("POST", "/api/v1/transactions", body);
        first.addHeader("X-Forwarded-For", "10.0.0.9, 10.0.0.1");
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(first, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());

        MockHttpServletRequest second = jsonRequest("POST", "/api/v1/transactions", body);
        second.addHeader("X-Forwarded-For", "10.0.0.9");
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(second, rejected, mock(FilterChain.class));
        assertThat(rejected.getStatus()).isEqualTo(429);
    }

    @Test
    void handlesInvalidJsonBodyAndNonJsonContentType() throws Exception {
        MockHttpServletRequest badJson = jsonRequest("PUT", "/api/v1/transactions", "{not-json");
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(badJson, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());

        MockHttpServletRequest plain = new MockHttpServletRequest("POST", "/api/v1/transactions");
        plain.setContentType("text/plain");
        plain.setContent("x".getBytes(StandardCharsets.UTF_8));
        FilterChain chain2 = mock(FilterChain.class);
        filter.doFilter(plain, new MockHttpServletResponse(), chain2);
        verify(chain2).doFilter(any(), any());
    }

    @Test
    void blankRemoteAddrFallsBackToUnknown() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");
        request.setRemoteAddr("  ");
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void blankUserAndMerchantIdsInJsonAreIgnored() throws Exception {
        String body = "{\"userId\":\"  \",\"merchantId\":\"\",\"amount\":1,\"category\":\"GROCERIES\"}";
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(jsonRequest("PATCH", "/api/v1/transactions", body), new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
    }

    private void assertAllowed(MockHttpServletResponse response) {
        assertThat(response.getStatus()).isNotEqualTo(429);
    }

    private MockHttpServletResponse postJson(String body) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(jsonRequest("POST", "/api/v1/transactions", body), response, mock(FilterChain.class));
        return response;
    }

    private static MockHttpServletRequest jsonRequest(String method, String uri, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
