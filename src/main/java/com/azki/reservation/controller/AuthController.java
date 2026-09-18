package com.azki.reservation.controller;

import com.azki.reservation.dto.auth.LoginRequestDto;
import com.azki.reservation.dto.auth.LoginResponseDto;
import com.azki.reservation.dto.auth.RegisterRequest;
import com.azki.reservation.dto.auth.RegisterResponse;
import com.azki.reservation.repository.UserRepository;
import com.azki.reservation.security.util.JwtUtil;
import com.azki.reservation.service.RegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
 * <p>提供普通用户注册和登录。注册不会签发 JWT，用户需继续调用登录接口。</p>
 */
@Tag(name = "用户认证", description = "普通用户注册、登录与身份令牌相关接口")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final RegistrationService registrationService;

    @Operation(
            summary = "注册普通用户",
            description = "创建角色固定为 USER 的账号。成功后不会自动签发 JWT，请调用登录接口获取令牌",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = RegisterRequest.class),
                            examples = @ExampleObject(
                                    name = "registerRequest",
                                    summary = "普通用户注册示例",
                                    value = """
                                    {"email":"user@example.com","username":"user123","password":"Test123456!"}
                                    """))))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "注册成功",
                    content = @Content(schema = @Schema(implementation = RegisterResponse.class))),
            @ApiResponse(responseCode = "400", description = "请求参数不合法"),
            @ApiResponse(responseCode = "409", description = "邮箱或用户名已被使用")
    })
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisterResponse response = registrationService.register(request);
        logger.info("Registered user account: {}", response.email());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

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
