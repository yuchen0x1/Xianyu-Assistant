package com.feijimiao.xianyuassistant.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发布物 Ed25519 签名校验测试：测试内生成独立密钥对，验证签名/篡改/换钥/空值行为。
 */
class ReleaseSignatureVerifierTest {

    @TempDir
    Path dir;

    private static KeyPair newKeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String sign(Path file, KeyPair keyPair) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(Files.readAllBytes(file));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static String spki(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    @Test
    void 正确签名通过校验() throws Exception {
        KeyPair keyPair = newKeyPair();
        Path file = dir.resolve("console.jar");
        Files.write(file, "payload-bytes-1".getBytes());

        assertThat(ReleaseSignatureVerifier.verify(file, sign(file, keyPair), spki(keyPair))).isTrue();
    }

    @Test
    void 内容被篡改后校验失败() throws Exception {
        KeyPair keyPair = newKeyPair();
        Path file = dir.resolve("console.jar");
        Files.write(file, "original".getBytes());
        String signature = sign(file, keyPair);
        Files.write(file, "tampered".getBytes());

        assertThat(ReleaseSignatureVerifier.verify(file, signature, spki(keyPair))).isFalse();
    }

    @Test
    void 换用其他密钥校验失败() throws Exception {
        KeyPair signerKeys = newKeyPair();
        KeyPair otherKeys = newKeyPair();
        Path file = dir.resolve("console.jar");
        Files.write(file, "payload".getBytes());

        assertThat(ReleaseSignatureVerifier.verify(file, sign(file, signerKeys), spki(otherKeys))).isFalse();
    }

    @Test
    void 非法签名内容或空值返回false不抛异常() throws Exception {
        KeyPair keyPair = newKeyPair();
        Path file = dir.resolve("console.jar");
        Files.write(file, "payload".getBytes());

        assertThat(ReleaseSignatureVerifier.verify(file, "not-base64!!!", spki(keyPair))).isFalse();
        assertThat(ReleaseSignatureVerifier.verify(file, null, spki(keyPair))).isFalse();
        assertThat(ReleaseSignatureVerifier.verify(file, "  ", spki(keyPair))).isFalse();
        assertThat(ReleaseSignatureVerifier.verify(file, sign(file, keyPair), null)).isFalse();
        assertThat(ReleaseSignatureVerifier.verify(file, sign(file, keyPair), "YWJj")).isFalse();
        assertThat(ReleaseSignatureVerifier.verify(dir.resolve("missing.jar"), "AAAA", spki(keyPair))).isFalse();
    }

    @Test
    void 签名文件读取() throws Exception {
        KeyPair keyPair = newKeyPair();
        Path file = dir.resolve("console.jar");
        Files.write(file, "payload".getBytes());
        String signature = sign(file, keyPair);
        Path sigFile = dir.resolve("console.jar.sig");
        Files.writeString(sigFile, signature + "\n");

        assertThat(ReleaseSignatureVerifier.readSignatureFile(sigFile)).isEqualTo(signature);
        assertThat(ReleaseSignatureVerifier.readSignatureFile(dir.resolve("nope.sig"))).isNull();
    }
}
