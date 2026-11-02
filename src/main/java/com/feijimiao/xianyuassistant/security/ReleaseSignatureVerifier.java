package com.feijimiao.xianyuassistant.security;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 发布物 Ed25519 签名校验工具。
 * <p>
 * SHA-256 校验文件与安装包同源于一个 Release，发布通道（仓库/令牌）被入侵时两者可被同时替换，
 * 摘要校验形同虚设。Ed25519 签名的私钥保存在发布方离线/CI Secret 中，绝不进入仓库与安装包；
 * 客户端仅内置验证公钥，对官方渠道更新强制验签，攻击者无法伪造发布物签名。
 * </p>
 * <p>签名文件格式：安装包原始字节的 Ed25519 签名的 Base64（与 openssl
 * {@code pkeyutl -sign -rawin} 的原始签名输出一致）；公钥格式：X.509 SubjectPublicKeyInfo DER 的 Base64
 * （与 openssl {@code pkey -pubout} 输出一致）。算法使用 JDK 内置 JCA（Java 15+ 原生 Ed25519），不引入自制密码学。</p>
 */
@Slf4j
public final class ReleaseSignatureVerifier {

    private static final String KEY_ALGORITHM = "Ed25519";

    private ReleaseSignatureVerifier() {
    }

    /**
     * 校验文件签名。
     *
     * @param filePath            被签名的文件
     * @param base64Signature     Ed25519 原始签名的 Base64（即 {@code <file>.sig} 内容）
     * @param base64SpkiPublicKey X.509 SPKI DER 编码公钥的 Base64
     * @return 签名与公钥匹配且覆盖完整文件内容时返回 true；任何解析/校验异常返回 false
     */
    public static boolean verify(Path filePath, String base64Signature, String base64SpkiPublicKey) {
        if (filePath == null || base64Signature == null || base64Signature.isBlank()
                || base64SpkiPublicKey == null || base64SpkiPublicKey.isBlank()) {
            return false;
        }
        try {
            byte[] spki = Base64.getDecoder().decode(base64SpkiPublicKey.trim());
            PublicKey publicKey = KeyFactory.getInstance(KEY_ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(spki));

            byte[] signature = Base64.getDecoder().decode(base64Signature.trim());
            Signature verifier = Signature.getInstance(KEY_ALGORITHM);
            verifier.initVerify(publicKey);
            try (InputStream in = Files.newInputStream(filePath)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    verifier.update(buffer, 0, read);
                }
            }
            return verifier.verify(signature);
        } catch (Exception e) {
            log.warn("[ReleaseSign] 发布物签名校验异常: file={}, errorType={}",
                    filePath.getFileName(), e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 读取签名文件内容（Base64 文本），读取失败返回 null。
     */
    public static String readSignatureFile(Path signatureFile) {
        if (signatureFile == null || Files.notExists(signatureFile)) {
            return null;
        }
        try {
            String text = Files.readString(signatureFile).trim();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            log.warn("[ReleaseSign] 签名文件读取失败: {}", signatureFile.getFileName());
            return null;
        }
    }
}
