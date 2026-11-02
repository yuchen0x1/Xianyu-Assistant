package com.feijimiao.xianyuassistant.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 敏感凭据（Cookie/Token）落库加密工具。
 * <p>
 * 使用 AES/GCM/NoPadding（Java 内置 javax.crypto），密钥 128 位。
 * 密钥来源优先级：
 * <ol>
 *   <li>环境变量 {@code XIANYU_ASSISTANT_CREDENTIAL_SECRET}（base64 / hex / 16 字符原文）</li>
 *   <li>本地密钥文件 {@code dbdata/credential-secret.key}（不存在时生成随机密钥并写入）</li>
 * </ol>
 * 密文输出格式：{@code AESGCM:} 前缀 + Base64(IV + ciphertext)。
 * {@link #decrypt(String)} 对无前缀或解密失败的数据原样返回，兼容历史明文落库数据。
 * </p>
 */
@Slf4j
@Component
public class CredentialCipher {

    /** 密文前缀，用于识别加密数据并兼容历史明文 */
    public static final String PREFIX = "AESGCM:";

    /** 环境变量名 */
    public static final String ENV_SECRET_NAME = "XIANYU_ASSISTANT_CREDENTIAL_SECRET";

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_BYTES = 16; // 128 位
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String KEY_FILE_PATH = "dbdata/credential-secret.key";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static volatile SecretKeySpec secretKey;

    /**
     * Spring 构造注入环境变量（可为空），为空时回退本地密钥文件。
     */
    public CredentialCipher(@Value("${XIANYU_ASSISTANT_CREDENTIAL_SECRET:}") String envSecret) {
        ensureKey(resolveEnvSecret(envSecret));
    }

    /**
     * 加密明文，返回带 {@code AESGCM:} 前缀的密文；输入为 null 时返回 null。
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, getKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = Arrays.copyOf(iv, IV_BYTES + encrypted.length);
            System.arraycopy(encrypted, 0, combined, IV_BYTES, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("敏感凭据加密失败", e);
        }
    }

    /**
     * 解密密文；无 {@code AESGCM:} 前缀或解密失败时原样返回输入（兼容历史明文数据）。
     */
    public static String decrypt(String ciphertext) {
        if (ciphertext == null || !ciphertext.startsWith(PREFIX)) {
            return ciphertext;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            if (decoded.length <= IV_BYTES) {
                return ciphertext;
            }
            byte[] iv = Arrays.copyOfRange(decoded, 0, IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(decoded, IV_BYTES, decoded.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(encrypted);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 密钥变更或数据损坏时降级返回原文，保证历史明文数据可读
            return ciphertext;
        }
    }

    private static String resolveEnvSecret(String injected) {
        if (injected != null && !injected.isBlank()) {
            return injected;
        }
        return System.getenv(ENV_SECRET_NAME);
    }

    private static SecretKeySpec getKey() {
        SecretKeySpec current = secretKey;
        if (current != null) {
            return current;
        }
        ensureKey(null);
        return secretKey;
    }

    private static synchronized void ensureKey(String envSecret) {
        if (secretKey != null) {
            return;
        }
        // 1. 环境变量优先
        String resolved = resolveEnvSecret(envSecret);
        if (resolved != null && !resolved.isBlank()) {
            SecretKeySpec parsed = parseSecret(resolved);
            if (parsed != null) {
                secretKey = parsed;
                return;
            }
            log.warn("[Cipher] 环境变量配置的密钥格式无法解析，回退本地密钥文件");
        }
        // 2. 本地密钥文件
        secretKey = loadOrCreateKey();
    }

    /**
     * 解析密钥：依次尝试 base64（解码后 16 字节）、hex（32 字符）、16 字符原文。
     */
    private static SecretKeySpec parseSecret(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // base64
        try {
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            if (decoded.length == KEY_BYTES) {
                return new SecretKeySpec(decoded, KEY_ALGORITHM);
            }
        } catch (IllegalArgumentException ignored) {
            // 不是合法 base64，继续尝试其它格式
        }
        // hex
        try {
            byte[] decoded = HexFormat.of().parseHex(trimmed);
            if (decoded.length == KEY_BYTES) {
                return new SecretKeySpec(decoded, KEY_ALGORITHM);
            }
        } catch (IllegalArgumentException ignored) {
            // 不是合法 hex，继续尝试原文
        }
        // 16 字符原文
        byte[] rawBytes = trimmed.getBytes(StandardCharsets.UTF_8);
        if (rawBytes.length == KEY_BYTES) {
            return new SecretKeySpec(rawBytes, KEY_ALGORITHM);
        }
        return null;
    }

    private static SecretKeySpec loadOrCreateKey() {
        Path path = Paths.get(KEY_FILE_PATH).toAbsolutePath().normalize();
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                byte[] keyBytes = new byte[KEY_BYTES];
                SECURE_RANDOM.nextBytes(keyBytes);
                String encoded = Base64.getEncoder().encodeToString(keyBytes);
                try {
                    Files.writeString(path, encoded, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    trySetRestrictedPermissions(path);
                    log.info("[Cipher] 已生成本地密钥文件: path={}", path);
                } catch (FileAlreadyExistsException ignored) {
                    // 并发初始化时另一进程已先创建密钥文件
                }
            }
            String persisted = Files.readString(path, StandardCharsets.UTF_8).trim();
            SecretKeySpec parsed = parseSecret(persisted);
            if (parsed == null) {
                throw new IllegalStateException("本地密钥文件内容无效: " + path);
            }
            return parsed;
        } catch (IOException e) {
            throw new IllegalStateException("无法读取或创建本地密钥文件: " + path, e);
        }
    }

    private static void trySetRestrictedPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            // 非 POSIX 文件系统（如 Windows）不支持时忽略
        }
    }
}
