package com.azki.reservation.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 用户注册请求。角色由服务端固定分配，客户端不能指定。 */
@Schema(description = "用户注册请求；注册成功后需继续调用登录接口获取 JWT")
public class RegisterRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Valid email format is required")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    @Schema(description = "用于登录的唯一邮箱", example = "user@example.com")
    private String email;

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    @Pattern(regexp = "^[A-Za-z0-9._-]+$",
            message = "Username may only contain letters, numbers, dots, underscores and hyphens")
    @Schema(description = "唯一用户名，长度为 3 至 50 个字符", example = "user123")
    private String username;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S+$",
            message = "Password must contain uppercase and lowercase letters, a number and a special character")
    @Schema(description = "8 至 72 个字符，须包含大小写字母、数字和特殊字符",
            example = "Test123456!", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
    private String password;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
