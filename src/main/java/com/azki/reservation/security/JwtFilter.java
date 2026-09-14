package com.azki.reservation.security;

import com.azki.reservation.repository.UserRepository;
import com.azki.reservation.security.util.JwtUtil;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
/**
 * JWT 身份解析过滤器。
 * 若请求携带有效 Bearer Token，则从 token 中取得邮箱、确认用户仍存在，并写入 SecurityContext；
 * 未携带或令牌无效时不主动报错，是否允许匿名访问由 SecurityConfig 决定。
 */
public class JwtFilter extends GenericFilter {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public JwtFilter(JwtUtil jwtUtil, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest http = (HttpServletRequest) request;
        String authHeader = http.getHeader("Authorization");

        // Bearer 前缀存在时才尝试解析，避免影响匿名接口。
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtUtil.isTokenValid(token)) {
                String email = jwtUtil.extractEmail(token);
                // 再查一次数据库，避免已删除用户继续凭旧 token 获得身份。
                userRepository.findByEmail(email).ifPresent(user -> {
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            user.getEmail(), null, List.of()
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            }
        }

        // 无论是否成功建立身份，都把请求交给后续安全规则决定能否访问。
        chain.doFilter(request, response);
    }
}
