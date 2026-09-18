package com.azki.reservation.filter;

import com.azki.reservation.config.RateLimitConfig;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 预约与登录接口限流过滤器。
 * Bucket 按“接口组 + 客户端 IP”隔离，登录请求不会挤占预约请求额度。
 */
@Component
@Order(1)
@ConditionalOnProperty(value = "reservation.rate-limiting.enabled", havingValue = "true", matchIfMissing = false)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String OVERFLOW_CLIENT = "overflow";

    private final RateLimitConfig config;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitConfig config) {
        this.config = config;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String bucketKey = routeGroup(request.getRequestURI()) + ":" + clientKey(request);
        Bucket bucket = buckets.computeIfAbsent(limitMapGrowth(bucketKey), ignored -> config.createBucket());

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", Long.toString(Math.max(1, config.getRefillPeriod().toSeconds())));
        response.getWriter().write("{\"message\":\"请求过于频繁，请稍后重试\"}");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/v1/me/reservations")
                && !uri.startsWith("/api/v1/me/reservation-requests")
                && !uri.equals("/api/v1/auth/login");
    }

    private String routeGroup(String uri) {
        return uri.equals("/api/v1/auth/login") ? "login" : "reservation";
    }

    private String clientKey(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
    }

    /** 防止伪造大量客户端地址导致进程内 Bucket 映射无限增长。 */
    private String limitMapGrowth(String requestedKey) {
        if (buckets.containsKey(requestedKey) || buckets.size() < config.getMaxTrackedClients()) {
            return requestedKey;
        }
        int separator = requestedKey.indexOf(':');
        String route = separator < 0 ? "request" : requestedKey.substring(0, separator);
        return route + ":" + OVERFLOW_CLIENT;
    }
}
