package com.azki.reservation.security.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

@Component
/** JWT 的生成、解析和签名校验工具。当前使用 HMAC 对称密钥。 */
public class JwtUtil {

    // HMAC 签名密钥。当前硬编码仅适合演示，部署时应从安全配置注入。
    private static final String secret = "mysecretkey12345678901234567890";
    // 令牌有效期：86,400,000 毫秒，即 1 天。
    private static final long expiration = 86400000;

    private Key getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
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
