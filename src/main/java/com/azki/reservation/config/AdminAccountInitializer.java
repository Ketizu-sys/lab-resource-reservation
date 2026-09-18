package com.azki.reservation.config;

import com.azki.reservation.entity.User;
import com.azki.reservation.entity.UserRole;
import com.azki.reservation.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 在开发环境按需创建演示管理员账号。
 *
 * <p>账号信息必须全部由环境变量提供。初始化器不会提升已有普通用户的权限，
 * 也不会覆盖现有账号或密码；发生冲突时直接阻止应用以错误配置启动。</p>
 */
@Component
@Profile("dev")
public class AdminAccountInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(AdminAccountInitializer.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{3,50}$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S{8,72}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminUsername;
    private final String adminPassword;

    public AdminAccountInitializer(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap.admin-email:}") String adminEmail,
            @Value("${app.bootstrap.admin-username:}") String adminUsername,
            @Value("${app.bootstrap.admin-password:}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = trim(adminEmail);
        this.adminUsername = trim(adminUsername);
        this.adminPassword = adminPassword == null ? "" : adminPassword;
    }

    @Override
    public void run(String... args) {
        if (adminEmail.isEmpty() && adminUsername.isEmpty() && adminPassword.isEmpty()) {
            logger.info("Development administrator bootstrap is not configured");
            return;
        }
        validateConfiguration();

        userRepository.findByEmail(adminEmail).ifPresentOrElse(existing -> {
            if (existing.getRole() != UserRole.ADMIN) {
                throw new IllegalStateException(
                        "Configured administrator email belongs to a non-admin account");
            }
            logger.info("Development administrator already exists: {}", adminEmail);
        }, this::createAdministrator);
    }

    private void createAdministrator() {
        if (userRepository.existsByUserName(adminUsername)) {
            throw new IllegalStateException("Configured administrator username is already in use");
        }

        User admin = new User();
        admin.setEmail(adminEmail);
        admin.setUserName(adminUsername);
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setRole(UserRole.ADMIN);

        try {
            userRepository.saveAndFlush(admin);
            logger.info("Created development administrator account: {}", adminEmail);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException(
                    "Administrator bootstrap conflicted with an existing account", ex);
        }
    }

    private void validateConfiguration() {
        if (!EMAIL_PATTERN.matcher(adminEmail).matches() || adminEmail.length() > 255) {
            throw new IllegalStateException("ADMIN_EMAIL must be a valid email address");
        }
        if (!USERNAME_PATTERN.matcher(adminUsername).matches()) {
            throw new IllegalStateException(
                    "ADMIN_USERNAME must be 3-50 characters using letters, numbers, dots, underscores or hyphens");
        }
        if (!PASSWORD_PATTERN.matcher(adminPassword).matches()) {
            throw new IllegalStateException(
                    "ADMIN_PASSWORD must be 8-72 characters and contain upper/lowercase letters, a number and a special character");
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
