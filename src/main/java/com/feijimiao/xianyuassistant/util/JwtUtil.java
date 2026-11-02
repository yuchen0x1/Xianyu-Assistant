package com.feijimiao.xianyuassistant.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT工具类
 * @author IAMLZY
 * @date 2026/4/22
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret:}")
    private String secret;

    @Value("${jwt.secret-file:dbdata/jwt-secret.key}")
    private String secretFile;

    @Value("${jwt.expiration:604800000}")
    private long expiration; // 默认7天（毫秒）；活跃会话经 /api/login/refresh 轮换续期

    private volatile SecretKey secretKey;

    /**
     * 生成JWT Token
     */
    public String generateToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        return createToken(claims, username);
    }

    /**
     * 创建Token
     */
    private String createToken(Map<String, Object> claims, String subject) {
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expiration);
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expireDate)
                .signWith(getSecretKey())
                .compact();
    }

    /**
     * 从Token中获取Claims
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSecretKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            log.warn("解析Token失败: errorType={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 从Token中获取用户ID
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            return null;
        }
        Object userId = claims.get("userId");
        if (userId instanceof Integer) {
            return ((Integer) userId).longValue();
        }
        if (userId instanceof Long) {
            return (Long) userId;
        }
        return null;
    }

    /**
     * 从Token中获取用户名
     */
    public String getUsernameFromToken(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            return null;
        }
        return claims.getSubject();
    }

    /**
     * 验证Token是否有效
     */
    public boolean validateToken(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            return false;
        }
        Date expiration = claims.getExpiration();
        return expiration != null && !expiration.before(new Date());
    }

    /**
     * 获取Token过期时间（毫秒）
     */
    public long getExpiration() {
        return expiration;
    }

    private SecretKey getSecretKey() {
        SecretKey currentKey = secretKey;
        if (currentKey != null) {
            return currentKey;
        }

        synchronized (this) {
            if (secretKey != null) {
                return secretKey;
            }

            if (secret == null || secret.isBlank()) {
                secretKey = Keys.hmacShaKeyFor(loadOrCreatePersistentSecret().getBytes(StandardCharsets.UTF_8));
                return secretKey;
            }

            byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
            if (secretBytes.length < 32) {
                throw new IllegalStateException("JWT密钥长度不能少于32字节");
            }
            secretKey = Keys.hmacShaKeyFor(secretBytes);
            return secretKey;
        }
    }

    private String loadOrCreatePersistentSecret() {
        Path path = Paths.get(secretFile).toAbsolutePath().normalize();
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                byte[] randomBytes = new byte[48];
                new SecureRandom().nextBytes(randomBytes);
                String generatedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
                try {
                    Files.writeString(path, generatedSecret, StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE_NEW,
                            java.nio.file.StandardOpenOption.WRITE);
                    log.info("[Auth] 已生成并保存本地JWT密钥: path={}", path);
                } catch (FileAlreadyExistsException ignored) {
                    // Another process initialized the same key file first.
                }
                restrictSecretFilePermissions(path);
            }

            String persistedSecret = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (persistedSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException("本地JWT密钥长度不能少于32字节");
            }
            return persistedSecret;
        } catch (IOException e) {
            throw new IllegalStateException("无法读取或创建本地JWT密钥文件: " + path, e);
        }
    }

    /**
     * 把密钥文件收敛为仅属主可读写（POSIX 下 rw-------），与 CredentialCipher 一致。
     * Windows 不支持 POSIX 权限，忽略即可，不影响功能。
     */
    private void restrictSecretFilePermissions(Path path) {
        try {
            java.nio.file.attribute.PosixFileAttributeView view =
                    Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class);
            if (view != null) {
                Files.setPosixFilePermissions(path,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
        } catch (Exception e) {
            log.warn("[Auth] 无法收紧JWT密钥文件权限（不影响启动）: path={}, errorType={}",
                    path, e.getClass().getSimpleName());
        }
    }
}
