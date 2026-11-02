package com.feijimiao.xianyuassistant.proxy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.service.SysSettingService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 代理池管理器：多代理配置 + 轮询轮换 + 失败黑名单（TTL）。
 *
 * <p>配置存储在 {@code xianyu_sys_setting} 表：
 * <ul>
 *   <li>{@code proxy_enabled}：总开关（"1" 启用）</li>
 *   <li>{@code proxy_list}：JSON 数组，每项 {@link ProxyConfig}</li>
 *   <li>{@code proxy_blacklist_ttl_seconds}：黑名单存活秒数，默认 300</li>
 *   <li>{@code proxy_max_retries}：单次请求最大尝试次数（含首次），默认 3</li>
 * </ul>
 * 配置变更后调用 {@link #reload()} 热更新，无需重启。</p>
 *
 * <p>默认关闭，配置开启后生效；当所有代理均在黑名单中时回退直连。</p>
 */
@Slf4j
@Component
public class ProxyPoolManager implements ProxyPoolAccessor {

    public static final String KEY_ENABLED = "proxy_enabled";
    public static final String KEY_LIST = "proxy_list";
    public static final String KEY_BLACKLIST_TTL = "proxy_blacklist_ttl_seconds";
    public static final String KEY_MAX_RETRIES = "proxy_max_retries";

    private static final long DEFAULT_BLACKLIST_TTL_MS = 300_000L;
    private static final int DEFAULT_MAX_RETRIES = 3;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private SysSettingService sysSettingService;

    /** 当前代理列表（不可变快照，替换即热更新） */
    private final AtomicReference<List<ProxyConfig>> proxies = new AtomicReference<>(Collections.emptyList());

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicInteger maxRetries = new AtomicInteger(DEFAULT_MAX_RETRIES);
    private final AtomicLong blacklistTtlMs = new AtomicLong(DEFAULT_BLACKLIST_TTL_MS);

    /** 轮询索引 */
    private final AtomicInteger rotationIndex = new AtomicInteger(0);

    /** 黑名单：proxyKey → 解禁时间戳（毫秒） */
    private final ConcurrentMap<String, Long> blacklist = new ConcurrentHashMap<>();

    /** 是否已注册全局 Authenticator（仅注册一次） */
    private volatile boolean authenticatorRegistered = false;

    @PostConstruct
    public void init() {
        // 静态注入到 HttpClientUtils，使其能感知代理池（先建立引用，避免首批请求走直连）
        com.feijimiao.xianyuassistant.utils.HttpClientUtils.setProxyAccessor(this);
        // 尽早加载一次配置（首次启动时代理配置可能尚未通过迁移脚本种子化，此时为禁用状态）
        reload();
        log.info("[ProxyPool] 初始化完成: enabled={}, proxyCount={}, blacklistTtlMs={}, maxRetries={}",
                enabled.get(), proxies.get().size(), blacklistTtlMs.get(), maxRetries.get());
    }

    /**
     * 应用就绪后再次加载：此时数据库迁移脚本已执行完毕，V3_3 种子的代理池配置已就绪。
     * 与 {@link #init()} 配合，保证首次启动也能正确读到默认配置。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        reload();
    }

    /**
     * 从数据库重新加载代理配置。幂等，配置变更后可重复调用。
     */
    public synchronized void reload() {
        try {
            String enabledStr = sysSettingService.getSettingValue(KEY_ENABLED);
            boolean newEnabled = "1".equals(enabledStr);
            enabled.set(newEnabled);

            String ttlStr = sysSettingService.getSettingValue(KEY_BLACKLIST_TTL);
            long ttl = parseLong(ttlStr, DEFAULT_BLACKLIST_TTL_MS / 1000) * 1000L;
            blacklistTtlMs.set(ttl);

            String retriesStr = sysSettingService.getSettingValue(KEY_MAX_RETRIES);
            int retries = (int) parseLong(retriesStr, DEFAULT_MAX_RETRIES);
            maxRetries.set(Math.max(1, retries));

            String listStr = sysSettingService.getSettingValue(KEY_LIST);
            List<ProxyConfig> parsed = parseProxyList(listStr);
            // 仅保留有效条目（host 非空、port 合法）
            List<ProxyConfig> valid = new ArrayList<>();
            if (parsed != null) {
                for (ProxyConfig c : parsed) {
                    if (c != null && c.getHost() != null && !c.getHost().isBlank() && c.getPort() > 0 && c.getPort() < 65536) {
                        valid.add(c);
                    }
                }
            }
            proxies.set(Collections.unmodifiableList(valid));

            // 切换配置时清空黑名单，避免旧黑名单误伤
            blacklist.clear();

            // 按需注册/注销代理认证器：Authenticator.setDefault 是 JVM 级全局状态，
            // 代理池停用或不再有认证代理时必须注销，避免残留认证逻辑影响其他 HTTP 出口。
            if (newEnabled && valid.stream().anyMatch(ProxyConfig::hasAuth)) {
                registerAuthenticatorIfNeeded();
            } else if (authenticatorRegistered) {
                Authenticator.setDefault(null);
                authenticatorRegistered = false;
                log.info("[ProxyPool] 已注销代理认证 Authenticator");
            }

            log.info("[ProxyPool] 配置已重载: enabled={}, proxyCount={}, blacklistTtlMs={}, maxRetries={}",
                    enabled.get(), valid.size(), blacklistTtlMs.get(), maxRetries.get());
        } catch (Exception e) {
            log.warn("[ProxyPool] 重载代理配置失败，禁用代理池: {}", e.getMessage());
            enabled.set(false);
            proxies.set(Collections.emptyList());
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled.get() && !proxies.get().isEmpty();
    }

    @Override
    public int maxAttempts() {
        return maxRetries.get();
    }

    @Override
    public Proxy acquireProxy() {
        if (!isEnabled()) {
            return null;
        }
        List<ProxyConfig> list = proxies.get();
        int size = list.size();
        if (size == 0) {
            return null;
        }
        long now = System.currentTimeMillis();
        // 最多遍历一圈，跳过黑名单中的代理
        for (int i = 0; i < size; i++) {
            int idx = (rotationIndex.getAndIncrement() & 0x7fffffff) % size;
            ProxyConfig cfg = list.get(idx);
            String key = cfg.key();
            Long until = blacklist.get(key);
            if (until != null) {
                if (until > now) {
                    // 仍在黑名单中
                    continue;
                }
                // TTL 已过，移出黑名单
                blacklist.remove(key, until);
            }
            return cfg.toJavaProxy();
        }
        // 全部在黑名单中
        return null;
    }

    @Override
    public void markSuccess(Proxy proxy) {
        if (proxy == null || proxy.address() == null) {
            return;
        }
        String key = proxyKey(proxy);
        blacklist.remove(key);
    }

    @Override
    public void markFailure(Proxy proxy, String reason) {
        if (proxy == null || proxy.address() == null) {
            return;
        }
        String key = proxyKey(proxy);
        long until = System.currentTimeMillis() + blacklistTtlMs.get();
        blacklist.put(key, until);
        log.warn("[ProxyPool] 代理加入黑名单: {} TTL={}ms 原因={}", key, blacklistTtlMs.get(),
                reason == null ? "unknown" : reason);
    }

    /**
     * 当前黑名单快照（供监控/前端展示）。
     */
    public Map<String, Long> blacklistSnapshot() {
        return Collections.unmodifiableMap(new java.util.HashMap<>(blacklist));
    }

    /**
     * 当前代理数量。
     */
    public int proxyCount() {
        return proxies.get().size();
    }

    /**
     * 当前是否启用。
     */
    public boolean enabled() {
        return enabled.get();
    }

    private String proxyKey(Proxy proxy) {
        if (proxy.address() instanceof InetSocketAddress addr) {
            return addr.getHostString() + ":" + addr.getPort();
        }
        return String.valueOf(proxy.address());
    }

    private List<ProxyConfig> parseProxyList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ProxyConfig>>() {});
        } catch (Exception e) {
            log.warn("[ProxyPool] proxy_list 解析失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private long parseLong(String s, long defaultVal) {
        if (s == null || s.isBlank()) {
            return defaultVal;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    /**
     * 注册全局 Authenticator 以支持代理用户名密码认证。
     *
     * <p>仅当至少一个代理配置了认证信息时注册。Authenticator 按 host:port 查找当前池中的凭证；
     * 查找失败返回 null（由 JDK 默认行为处理）。</p>
     */
    private void registerAuthenticatorIfNeeded() {
        if (authenticatorRegistered) {
            return;
        }
        try {
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() != RequestorType.PROXY) {
                        return null;
                    }
                    String host = getRequestingHost();
                    int port = getRequestingPort();
                    if (host == null || port <= 0) {
                        return null;
                    }
                    String lookup = host + ":" + port;
                    for (ProxyConfig cfg : proxies.get()) {
                        if (cfg.hasAuth() && cfg.key().equals(lookup)) {
                            return new PasswordAuthentication(cfg.getUsername(),
                                    cfg.getPassword() == null ? new char[0] : cfg.getPassword().toCharArray());
                        }
                    }
                    return null;
                }
            });
            authenticatorRegistered = true;
            log.info("[ProxyPool] 已注册代理认证 Authenticator");
        } catch (SecurityException e) {
            log.warn("[ProxyPool] 注册代理认证 Authenticator 失败：{}", e.getMessage());
        }
    }
}
