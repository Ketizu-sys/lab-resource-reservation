package com.azki.reservation.config;

import com.azki.reservation.support.ContainerIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证生成的 OpenAPI 文档与实际 Spring Security 访问规则保持一致。 */
@SpringBootTest(properties = "management.server.port=8080")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpenApiDocumentationIntegrationTest extends ContainerIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocsShouldDeclareJwtBearerOnlyForProtectedOperations() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/me/reservations'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/me/reservation-requests'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/me/reservation-requests/{requestId}'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/admin/slots/batches'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/auth/login']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/reservations']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/reservations/auto']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/reservations/reserve']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/admin/resources'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/resources'].get.parameters[?(@.name == 'page')].in").value("query"))
                .andExpect(jsonPath("$.paths['/api/v1/resources'].get.parameters[?(@.name == 'page')].description")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("page=0"))))
                .andExpect(jsonPath("$.paths['/api/v1/resources'].get.parameters[?(@.name == 'sort')].in").value("query"))
                .andExpect(jsonPath("$.paths['/api/v1/resources'].get.parameters[?(@.name == 'sort')].example[0]")
                        .value("id,asc"));
    }
}
