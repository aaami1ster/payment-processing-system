package com.example.payment.api.filter;

import com.example.payment.api.response.ApiError;
import com.example.payment.api.response.ApiResponse;
import com.example.payment.common.logging.LogFactory;
import com.example.payment.common.web.CachedBodyHttpServletRequest;
import com.example.payment.common.web.RequestIdFilter;
import com.example.payment.config.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * In-process Bucket4j limits for {@code /api/v1/**} keyed by userId, merchantId, and/or IP.
 * Independent of fraud Rule 2 — over-limit never reaches handlers (no txn/outbox writes).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LogFactory.getLogger(RateLimitFilter.class);

    private static final Pattern USER_PATH = Pattern.compile("^/api/v1/users/([^/]+)$");

    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        HttpServletRequest effective = request;
        String userId = null;
        String merchantId = null;

        Matcher userPath = USER_PATH.matcher(request.getRequestURI());
        if (userPath.matches()) {
            userId = userPath.group(1);
        }

        String method = request.getMethod();
        boolean mayHaveJsonBody = "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method);
        if (mayHaveJsonBody && isJson(request)) {
            CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
            effective = cached;
            JsonIds ids = extractIds(cached.getCachedBody());
            if (ids.userId() != null) {
                userId = ids.userId();
            }
            if (ids.merchantId() != null) {
                merchantId = ids.merchantId();
            }
        }

        if (userId != null && !userId.isBlank()) {
            ConsumptionProbe probe = probe("user:" + userId.trim(), properties.getUser());
            if (!probe.isConsumed()) {
                reject(request, response, probe);
                return;
            }
        }
        if (merchantId != null && !merchantId.isBlank()) {
            ConsumptionProbe probe = probe("merchant:" + merchantId.trim(), properties.getMerchant());
            if (!probe.isConsumed()) {
                reject(request, response, probe);
                return;
            }
        }

        ConsumptionProbe ipProbe = probe("ip:" + clientIp(request), properties.getIp());
        if (!ipProbe.isConsumed()) {
            reject(request, response, ipProbe);
            return;
        }

        filterChain.doFilter(effective, response);
    }

    private ConsumptionProbe probe(String key, RateLimitProperties.Limit limit) {
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> newBucket(limit));
        return bucket.tryConsumeAndReturnRemaining(1);
    }

    private static Bucket newBucket(RateLimitProperties.Limit limit) {
        int capacity = Math.max(1, limit.getCapacity());
        int windowSeconds = Math.max(1, limit.getWindowSeconds());
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, Duration.ofSeconds(windowSeconds))
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, ConsumptionProbe probe)
            throws IOException {
        long retryAfterSeconds = Math.max(1L, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
        String requestId = RequestIdFilter.resolve(request);
        log.info(
                "rate_limit.exceeded requestId={} path={} retryAfterSeconds={}",
                requestId,
                request.getRequestURI(),
                retryAfterSeconds);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiResponse<Void> body = ApiResponse.failure(
                "Rate limit exceeded",
                ApiError.of("RATE_LIMIT_EXCEEDED", "Too many requests; retry later"),
                requestId);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private JsonIds extractIds(byte[] body) {
        if (body == null || body.length == 0) {
            return JsonIds.EMPTY;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return new JsonIds(textOrNull(root, "userId"), textOrNull(root, "merchantId"));
        } catch (Exception ex) {
            log.debug("rate_limit.body_parse_skipped error={}", ex.toString());
            return JsonIds.EMPTY;
        }
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asString();
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean isJson(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("application/json");
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    private record JsonIds(String userId, String merchantId) {
        private static final JsonIds EMPTY = new JsonIds(null, null);
    }
}
