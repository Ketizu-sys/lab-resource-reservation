package com.azki.reservation.security.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

@Component
/** JWT 的生成、解析和签名校验工具，使用由部署环境提供的 HMAC 对称密钥。 */
public class JwtUtil {

    private final String secret;
    private final long expiration;

    /**
     * JWT 密钥优先读取 JWT_SECRET 环境变量，也允许测试通过 security.jwt.secret 注入。
     * HS256 要求至少 256 位密钥；启动时立即校验可以避免使用弱密钥运行。
     */
    public JwtUtil(
            @Value("${JWT_SECRET:${security.jwt.secret:}}") String secret,
            @Value("${JWT_EXPIRATION_MS:${security.jwt.expiration-ms:86400000}}") long expiration) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 UTF-8 bytes");
        }
        if (expiration <= 0) {
            throw new IllegalArgumentException("JWT expiration must be greater than zero");
        }
        this.secret = secret;
        this.expiration = expiration;
    }

    private Key getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成以用户邮箱为 subject 的 JWT。
     * @param email 用户邮箱
     * @return 已签名的令牌字符串
     */
    public String generateToken(String email) {
        return Jwts.builder()
                .setSubject(email)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getKey())
                .compact();
    }
    /**
     * 验证签名并提取 subject 中的用户邮箱。
     * @param token JWT 字符串
     * @return 用户邮箱
     */
    public String extractEmail(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getKey())
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    /**
     * 读取令牌过期时间并转换成服务器默认时区的 LocalDateTime。
     * @param token JWT 字符串
     * @return 本地时区的过期时间
     */
    public LocalDateTime getExpirationDate(String token) {
        Date expiration = extractAllClaims(token).getExpiration();
        return expiration.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    /** 解析并返回令牌中的全部声明；签名或格式错误时由 JJWT 抛异常。 */
    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * 判断令牌能否通过签名、格式和过期时间校验。
     * @param token JWT 字符串
     * @return 有效返回 true，任何 JWT/参数异常均返回 false
     */
    public boolean isTokenValid(String token) {
        try {
            extractEmail(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
