package com.azki.reservation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.azki.reservation.security.JwtFilter;

@Configuration
@EnableWebSecurity
/**
 * Spring Security 过滤链配置。
 * 使用无状态会话，并把 JWT 过滤器放在用户名密码过滤器之前解析调用方身份。
 */
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    public SecurityConfig(JwtFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // REST API 不使用服务端 Session，因此关闭 CSRF 并采用 STATELESS 策略。
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                            "/api/auth/**",
                            "/swagger-ui/**",
                            "/v3/api-docs/**",
                            "/api/v1/reservations/**" // 当前预约接口全部匿名开放；生产环境通常应收紧此规则。
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(sess -> sess
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
