package com.azki.reservation.controller;

import com.azki.reservation.dto.auth.LoginRequestDto;
import com.azki.reservation.dto.auth.LoginResponseDto;
import com.azki.reservation.repository.UserRepository;
import com.azki.reservation.security.util.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
/**
 * 用户认证接口。
 *
 * <p>当前只提供登录：校验数据库中的用户密码，成功后签发 JWT。</p>
 */
@Tag(name = "用户认证", description = "用户登录与身份令牌相关接口")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    @Operation(summary = "用户登录", description = "校验邮箱和密码，成功后返回 JWT")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登录成功",
                    content = @Content(schema = @Schema(implementation = LoginResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "邮箱或密码错误"),
            @ApiResponse(responseCode = "400", description = "请求参数不合法")
    })
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequestDto loginRequest) {
        logger.debug("Login attempt for email: {}", loginRequest.getEmail());

        return userRepository.findByEmail(loginRequest.getEmail())
                .filter(user -> passwordEncoder.matches(loginRequest.getPassword(), user.getPassword()))
                .map(user -> {
                    // 以邮箱作为 JWT 的 subject，后续 JwtFilter 会从中还原用户身份。
                    String token = jwtUtil.generateToken(user.getEmail());

                    // 除令牌外一并返回用户信息和过期时间，方便客户端保存登录态。
                    LoginResponseDto response = LoginResponseDto.builder()
                            .token(token)
                            .tokenType("Bearer")
                            .email(user.getEmail())
                            .userName(user.getUserName())
                            .expiresAt(jwtUtil.getExpirationDate(token))
                            .build();

                    logger.info("Successful login for user: {}", user.getEmail());
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> {
                    logger.warn("Failed login attempt for email: {}", loginRequest.getEmail());
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
                });
    }
}
