package com.azki.reservation.filter;

import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 预约接口限流过滤器。
 * 当前所有调用者共用一个进程内 Bucket；后续可改为按用户或 IP 分桶。
 */
@Component
@Order(1)
@ConditionalOnProperty(value = "reservation.rate-limiting.enabled", havingValue = "true", matchIfMissing = false)
public class RateLimitFilter extends OncePerRequestFilter {

    private final Bucket bucket;

    public RateLimitFilter(Bucket bucket) {
        this.bucket = bucket;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 只限制预约接口，登录、Swagger、健康检查等请求直接放行。
        if (request.getRequestURI().startsWith("/api/reservations")) {
            if (bucket.tryConsume(1)) {
                // 成功取得一个令牌，继续进入后续过滤器和控制器。
                filterChain.doFilter(request, response);
            } else {
                // 令牌不足时立即返回 429，不再执行后续业务。
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.getWriter().write("Rate limit exceeded. Please try again later.");
                response.getWriter().flush();
            }
        } else {
            // 不属于限流路径，原样传递。
            filterChain.doFilter(request, response);
        }
    }
}
