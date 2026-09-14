package com.azki.reservation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
/** 应用启动后在日志中打印 Swagger UI 地址，方便开发人员快速打开接口文档。 */
public class SwaggerUrlLoggerConfig {

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @EventListener(ApplicationStartedEvent.class)
    public void logSwaggerUiUrl() {
        // 同时考虑自定义服务端口和 context-path。
        String baseUrl = "http://localhost:" + serverPort + contextPath;
        String swaggerUrl = baseUrl + "/swagger-ui/index.html";

        log.info("");
        log.info("┌───────────────────────────────────────────────────┐");
        log.info("│                                                   │");
        log.info("│   API Documentation is available at:              │");
        log.info("│   {}", swaggerUrl);
        log.info("│                                                   │");
        log.info("└───────────────────────────────────────────────────┘");
        log.info("");
    }
}
