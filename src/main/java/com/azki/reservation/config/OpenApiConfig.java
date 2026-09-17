package com.azki.reservation.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

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

    /** 统一说明 Spring Data 分页参数，避免 Swagger 把 page=1 误解成第一页。 */
    @Bean
    OpenApiCustomizer pageableParameterCustomizer() {
        return openApi -> openApi.getPaths().values().stream()
                .flatMap(pathItem -> pathItem.readOperations().stream())
                .map(Operation::getParameters)
                .filter(parameters -> parameters != null)
                .flatMap(List::stream)
                .filter(parameter -> "query".equals(parameter.getIn()))
                .forEach(this::documentPageableParameter);
    }

    private void documentPageableParameter(Parameter parameter) {
        switch (parameter.getName()) {
            case "page" -> {
                parameter.setDescription("从 0 开始的页码；page=0 表示第一页，page=1 表示第二页");
                parameter.setExample(0);
            }
            case "size" -> {
                parameter.setDescription("每页返回的记录数");
                parameter.setExample(20);
            }
            case "sort" -> {
                parameter.setDescription("排序条件，格式为 属性名,方向；例如 id,asc 或 name,desc");
                parameter.setExample(List.of("id,asc"));
            }
            default -> {
                // 非 Pageable 查询参数保持 springdoc 生成的原始文档。
            }
        }
    }
}
