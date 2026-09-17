package com.azki.reservation.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 的 JWT Bearer 认证方案。
 *
 * <p>这里只注册 Swagger UI 可使用的认证方式，不添加全局安全要求。
 * 各 Controller 根据 {@link com.azki.reservation.config.SecurityConfig} 的实际规则自行声明，
 * 从而保证登录接口仍在文档中显示为匿名接口。</p>
 */
@Configuration
@SecurityScheme(
        name = OpenApiConfig.BEARER_AUTH,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";
}
