package com.feijimiao.xianyuassistant.security;

import com.feijimiao.xianyuassistant.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 服务端点（ai_base_url / ai_embedding_base_url）安全校验器。
 * <p>
 * AI API Key 会以 {@code Authorization: Bearer} 发往该端点。若允许把端点配到内网或本机任意地址，
 * 持有 settings:write 的低权限账号可借服务端发起内网请求并外带管理员配置的 API Key（SSRF/凭据外带）。
 * 因此端点必须满足：
 * <ol>
 *   <li>绝对 URL，scheme 为 https（本地模型等 http 端点必须显式加入白名单）；</li>
 *   <li>不带 user-info，host 非空；</li>
 *   <li>解析出的所有地址均为公网地址（拒绝回环/私网/链路本地/组播/唯一本地等）；</li>
 *   <li>白名单主机（{@code app.security.ai-endpoint.extra-allowed-hosts}，逗号分隔）豁免 1/3，
 *       用于 ollama/LM Studio 等本地模型端点，改动会记录审计日志。</li>
 * </ol>
 * 在配置保存时与 ChatClient/VectorStore 构建时双重调用（备份导入直接写库，绕过保存路径）。
 * 已知残留风险：校验时与实际请求时的 DNS 解析之间存在理论上的 rebinding 窗口，见安全架构文档。
 * </p>
 */
@Slf4j
@Component
public class AiEndpointValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final Set<String> extraAllowedHosts;

    public AiEndpointValidator(
            @Value("${app.security.ai-endpoint.extra-allowed-hosts:}") String extraAllowedHosts) {
        this.extraAllowedHosts = parseHosts(extraAllowedHosts);
    }

    /**
     * 校验 AI 端点 URL，不合法时抛出 BusinessException。
     */
    public void validate(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return;
        }
        String trimmed = rawUrl.trim();

        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("AI服务地址格式不正确");
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BusinessException("AI服务地址必须是包含主机的完整URL，例如 https://dashscope.aliyuncs.com/compatible-mode");
        }
        if (!ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new BusinessException("AI服务地址仅支持 http/https 协议");
        }
        if (uri.getUserInfo() != null) {
            throw new BusinessException("AI服务地址不允许携带用户名密码");
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean whitelisted = isWhitelisted(host);

        if (!whitelisted && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new BusinessException("AI服务地址必须使用 https（本地模型端点请联系管理员加入白名单）");
        }

        if (!whitelisted) {
            assertPublicHost(host);
        } else {
            log.info("[AiEndpoint] 使用白名单AI端点: host={}", host);
        }
    }

    private boolean isWhitelisted(String host) {
        return extraAllowedHosts.contains(host)
                // 纯 IP 字面量按去零形式匹配（如 127.0.0.1 与 [::1]）
                || extraAllowedHosts.contains(normalizeIpLiteral(host));
    }

    private void assertPublicHost(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new BusinessException("AI服务地址无法解析: " + host);
        }
        if (addresses == null || addresses.length == 0) {
            throw new BusinessException("AI服务地址无法解析: " + host);
        }
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                log.warn("[AiEndpoint] 拒绝非公网AI端点: host={}, addressType={}",
                        host, address.getClass().getSimpleName());
                throw new BusinessException("AI服务地址不允许指向内网或本机地址: " + host);
            }
        }
    }

    private boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || address.isSiteLocalAddress()) {
            return false;
        }
        if (address instanceof Inet4Address) {
            byte[] b = address.getAddress();
            int first = b[0] & 0xFF;
            // 0.0.0.0/8、100.64.0.0/10（CGNAT）、240.0.0.0/4（保留/广播）
            if (first == 0 || first == 255) {
                return false;
            }
            if (first == 100 && (b[1] & 0xFF) >= 64 && (b[1] & 0xFF) <= 127) {
                return false;
            }
            return true;
        }
        if (address instanceof Inet6Address) {
            byte[] b = address.getAddress();
            // fc00::/7 唯一本地地址（ULA）
            if ((b[0] & 0xFE) == 0xFC) {
                return false;
            }
            // ::ffff:x.x.x.x 形式的 IPv4 映射地址按 IPv4 规则判断
            boolean v4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    v4Mapped = false;
                    break;
                }
            }
            if (v4Mapped && b[10] == (byte) 0xFF && b[11] == (byte) 0xFF) {
                byte[] v4 = Arrays.copyOfRange(b, 12, 16);
                try {
                    return isPublicAddress(Inet4Address.getByAddress(v4));
                } catch (UnknownHostException ignored) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private Set<String> parseHosts(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private String normalizeIpLiteral(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            // getByName 对主机名也会触发解析；这里仅用于规范化已配置的白名单条目，
            // 白名单通常为少量本地地址，解析失败时回退原文。
            return address.getHostAddress().toLowerCase(Locale.ROOT);
        } catch (UnknownHostException e) {
            return host;
        }
    }
}
