package com.example.payment.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts or generates {@code X-Request-Id} and echoes it on the response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTR = "requestId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = "req_" + UUID.randomUUID().toString().replace("-", "");
        }
        request.setAttribute(ATTR, requestId);
        response.setHeader(HEADER, requestId);
        filterChain.doFilter(request, response);
    }

    public static String resolve(HttpServletRequest request) {
        Object value = request.getAttribute(ATTR);
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        String header = request.getHeader(HEADER);
        if (header != null && !header.isBlank()) {
            return header;
        }
        return "req_unknown";
    }
}
