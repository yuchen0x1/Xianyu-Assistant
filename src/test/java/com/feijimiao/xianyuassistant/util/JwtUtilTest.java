package com.feijimiao.xianyuassistant.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    @Test
    void blankConfiguredSecretPersistsAcrossInstances(@TempDir Path tempDir) {
        Path secretFile = tempDir.resolve("jwt-secret.key");
        JwtUtil issuer = jwtUtilWith("", secretFile);
        JwtUtil verifier = jwtUtilWith("", secretFile);

        String token = issuer.generateToken(7L, "admin");

        assertThat(secretFile).exists();
        assertThat(verifier.validateToken(token)).isTrue();
    }

    @Test
    void configuredSecretCanValidateTokensAcrossInstances() {
        String configuredSecret = "a-secure-secret-with-at-least-32-bytes";
        JwtUtil issuer = jwtUtilWith(configuredSecret, null);
        JwtUtil verifier = jwtUtilWith(configuredSecret, null);

        String token = issuer.generateToken(7L, "admin");

        assertThat(verifier.validateToken(token)).isTrue();
    }

    @Test
    void explicitlyConfiguredWeakSecretIsRejected() {
        JwtUtil jwtUtil = jwtUtilWith("too-short", null);

        assertThatThrownBy(() -> jwtUtil.generateToken(7L, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    private JwtUtil jwtUtilWith(String secret, Path secretFile) {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", secret);
        ReflectionTestUtils.setField(jwtUtil, "secretFile", secretFile != null ? secretFile.toString() : "dbdata/test-jwt-secret.key");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 60_000L);
        return jwtUtil;
    }
}
