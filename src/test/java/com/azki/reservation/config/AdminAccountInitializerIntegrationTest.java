package com.azki.reservation.config;

import com.azki.reservation.entity.UserRole;
import com.azki.reservation.repository.UserRepository;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 PostgreSQL 验证 dev 管理员初始化会安全落库并加密密码。 */
@SpringBootTest(properties = {
        "management.server.port=8080",
        "reservation.scheduling.enabled=false",
        "security.jwt.secret=test-only-jwt-secret-with-at-least-32-bytes",
        "app.bootstrap.admin-email=bootstrap-admin@example.com",
        "app.bootstrap.admin-username=bootstrap-admin",
        "app.bootstrap.admin-password=Admin123456!"
})
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminAccountInitializerIntegrationTest extends ContainerIntegrationTestSupport {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void configuredAdministratorShouldBeCreatedInPostgresWithEncryptedPassword() {
        var admin = userRepository.findByEmail("bootstrap-admin@example.com").orElseThrow();

        assertEquals("bootstrap-admin", admin.getUserName());
        assertEquals(UserRole.ADMIN, admin.getRole());
        assertTrue(passwordEncoder.matches("Admin123456!", admin.getPassword()));
    }
}
