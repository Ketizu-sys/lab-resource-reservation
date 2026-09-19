package com.azki.reservation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证全局异常处理器不会把内部异常细节下发到客户端。
 *
 * <p>ApiError 的 error 字段必须是固定分类文案，message 字段必须是可读提示；
 * 原始异常的 SQL、约束名、Hibernate/JDBC 信息只允许出现在服务端日志中。</p>
 */
class GlobalExceptionHandlerLeakTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void databaseIntegrityViolationShouldReturnConflictWithoutSql() throws Exception {
        String body = mockMvc.perform(get("/probe/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Data Integrity Violation"))
                .andReturn().getResponse().getContentAsString();

        assertNoInternalLeakage(body);
        assertTrue(body.contains("refresh and try again"), body);
    }

    @Test
    void unknownExceptionShouldReturnGenericInternalServerError() throws Exception {
        String body = mockMvc.perform(get("/probe/unknown"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andReturn().getResponse().getContentAsString();

        assertNoInternalLeakage(body);
        assertFalse(body.contains("SECRET-DB-DETAIL"), body);
    }

    @Test
    void businessExceptionShouldStillReturnReadableBusinessMessage() throws Exception {
        String body = mockMvc.perform(get("/probe/business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Time slot has reservation history and cannot be deleted"))
                .andReturn().getResponse().getContentAsString();

        assertNoInternalLeakage(body);
    }

    private void assertNoInternalLeakage(String body) {
        String lower = body.toLowerCase();
        List<String> forbidden = List.of(
                "sql", "constraint", "fk_reservation_slot", "hibernate", "jdbc",
                "org.postgresql", "psqlexception", "secret-db-detail", "stacktrace", "available_slot");
        forbidden.forEach(token -> assertFalse(lower.contains(token),
                "响应体不应包含内部实现信息 '" + token + "'，实际响应：" + body));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/data-integrity")
        public String dataIntegrity() {
            throw new DataIntegrityViolationException(
                    "could not execute statement; SQL [delete from available_slot]; "
                            + "constraint [fk_reservation_slot] violates foreign key constraint");
        }

        @GetMapping("/probe/unknown")
        public String unknown() {
            throw new IllegalStateException("SECRET-DB-DETAIL: org.hibernate.JDBCException at available_slot");
        }

        @GetMapping("/probe/business")
        public String business() {
            throw new com.azki.reservation.exception.BusinessException(
                    "Time slot has reservation history and cannot be deleted");
        }
    }
}
