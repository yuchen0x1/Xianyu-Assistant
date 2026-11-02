package com.feijimiao.xianyuassistant.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DataPanel 查询使用的显式账号范围。
 * accountIds 为 null 仅表示 ADMIN 全量可见；空列表表示拒绝全部。
 */
public final class DataPanelAccountScope {
    private final Long userId;
    private final List<Long> accountIds;
    private final String fingerprint;

    private DataPanelAccountScope(Long userId, List<Long> accountIds) {
        this.userId = userId;
        if (accountIds == null) {
            this.accountIds = null;
            this.fingerprint = "ALL";
        } else {
            List<Long> normalized = new ArrayList<>();
            for (Long accountId : accountIds) {
                if (accountId != null) normalized.add(accountId);
            }
            Collections.sort(normalized);
            this.accountIds = Collections.unmodifiableList(normalized);
            this.fingerprint = fingerprint(normalized);
        }
    }

    public static DataPanelAccountScope all(Long userId) {
        return new DataPanelAccountScope(userId, null);
    }

    public static DataPanelAccountScope restricted(Long userId, List<Long> accountIds) {
        return new DataPanelAccountScope(userId, accountIds == null ? List.of() : accountIds);
    }

    public Long getUserId() {
        return userId;
    }

    /** null means all accounts, an empty list means no accounts. */
    public List<Long> getAccountIds() {
        return accountIds;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String cacheKeyPart() {
        return (userId == null ? "anonymous" : userId) + ":" + fingerprint;
    }

    private static String fingerprint(List<Long> ids) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(ids.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) result.append(String.format("%02x", b));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
