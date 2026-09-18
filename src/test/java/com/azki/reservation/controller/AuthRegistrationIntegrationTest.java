package com.azki.reservation.controller;

import com.azki.reservation.entity.UserRole;
import com.azki.reservation.repository.UserRepository;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真实 PostgreSQL 验证注册、唯一约束、密码加密以及注册后的登录流程。 */
@SpringBootTest(properties = "management.server.port=8080")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthRegistrationIntegrationTest extends ContainerIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void anonymousUserShouldRegisterAndPasswordShouldBeHashed() throws Exception {
        String suffix = suffix();
        String email = "register-" + suffix + "@example.com";
        String username = "register_" + suffix;
        String password = "Test123456!";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, username, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());

        var saved = userRepository.findByEmail(email).orElseThrow();
        assertEquals(UserRole.USER, saved.getRole());
        assertNotEquals(password, saved.getPassword());
        assertTrue(passwordEncoder.matches(password, saved.getPassword()));
    }

    @Test
    void duplicateEmailShouldReturnConflict() throws Exception {
        String suffix = suffix();
        String email = "duplicate-email-" + suffix + "@example.com";

        register(email, "first_" + suffix, "Test123456!");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, "second_" + suffix, "Test123456!")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void duplicateUsernameShouldReturnConflict() throws Exception {
        String suffix = suffix();
        String username = "duplicate_" + suffix;

        register("first-" + suffix + "@example.com", username, "Test123456!");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("second-" + suffix + "@example.com", username, "Test123456!")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void invalidEmailShouldReturnBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("not-an-email", "valid_user", "Test123456!")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void blankPasswordShouldReturnBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("blank-password@example.com", "blank_password", "")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void weakOrShortPasswordShouldReturnBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("weak-password@example.com", "weak_password", "password")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("short-password@example.com", "short_password", "T1!a")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void roleInRequestMustNotCreateAdministrator() throws Exception {
        String suffix = suffix();
        String email = "role-" + suffix + "@example.com";
        String username = "role_" + suffix;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","username":"%s","password":"Test123456!","role":"ADMIN"}
                                """.formatted(email, username)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"));

        assertEquals(UserRole.USER, userRepository.findByEmail(email).orElseThrow().getRole());
    }

    @Test
    void registeredUserShouldLoginAndReceiveJwt() throws Exception {
        String suffix = suffix();
        String email = "login-after-register-" + suffix + "@example.com";
        String password = "Test123456!";
        register(email, "login_" + suffix, password);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void loginShouldReturnCurrentAdministratorRole() throws Exception {
        String suffix = suffix();
        String email = "admin-login-" + suffix + "@example.com";
        String password = "Test123456!";
        jdbcTemplate.update("""
                INSERT INTO users
                    (email, user_name, password, role, created_by, created_date, version)
                VALUES (?, ?, ?, 'ADMIN', 'test', CURRENT_TIMESTAMP, 0)
                """, email, "admin_" + suffix, passwordEncoder.encode(password));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void postgresShouldEnforceEmailAndUsernameUniqueConstraints() {
        String suffix = suffix();
        String email = "db-unique-" + suffix + "@example.com";
        String username = "db_unique_" + suffix;
        String passwordHash = passwordEncoder.encode("Test123456!");

        insertUser(email, username, passwordHash);

        assertThrows(DataIntegrityViolationException.class,
                () -> insertUser(email, "other_" + suffix, passwordHash));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertUser("other-" + suffix + "@example.com", username, passwordHash));
    }

    private void register(String email, String username, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, username, password)))
                .andExpect(status().isCreated());
    }

    private void insertUser(String email, String username, String passwordHash) {
        jdbcTemplate.update("""
                INSERT INTO users
                    (email, user_name, password, role, created_by, created_date, version)
                VALUES (?, ?, ?, 'USER', 'test', CURRENT_TIMESTAMP, 0)
                """, email, username, passwordHash);
    }

    private String registerJson(String email, String username, String password) {
        return """
                {"email":"%s","username":"%s","password":"%s"}
                """.formatted(email, username, password);
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
