package com.azki.reservation.service;

import com.azki.reservation.dto.auth.RegisterRequest;
import com.azki.reservation.dto.auth.RegisterResponse;
import com.azki.reservation.entity.User;
import com.azki.reservation.entity.UserRole;
import com.azki.reservation.exception.UserRegistrationConflictException;
import com.azki.reservation.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 创建普通用户账号，并把唯一约束冲突转换成稳定的 409 业务错误。 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = request.getEmail().trim();
        String username = request.getUsername().trim();

        if (userRepository.existsByEmail(email)) {
            throw new UserRegistrationConflictException("Email is already registered");
        }
        if (userRepository.existsByUserName(username)) {
            throw new UserRegistrationConflictException("Username is already registered");
        }

        User user = new User();
        user.setEmail(email);
        user.setUserName(username);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(UserRole.USER);

        try {
            User saved = userRepository.saveAndFlush(user);
            return new RegisterResponse(
                    saved.getId(),
                    saved.getEmail(),
                    saved.getUserName(),
                    saved.getRole(),
                    saved.getCreatedDate());
        } catch (DataIntegrityViolationException ex) {
            // 预检查与 INSERT 之间仍可能有并发请求，最终以数据库唯一约束为准。
            throw new UserRegistrationConflictException("Email or username is already registered", ex);
        }
    }
}
