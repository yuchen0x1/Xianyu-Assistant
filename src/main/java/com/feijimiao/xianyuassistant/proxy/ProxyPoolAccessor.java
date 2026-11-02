package com.feijimiao.xianyuassistant.proxy;

import java.net.Proxy;

/**
 * 代理池访问器接口。
 *
 * <p>由 {@link ProxyPoolManager} 实现，并被静态工具类 {@code HttpClientUtils}
 * 通过静态注入的方式引用，避免静态工具对 Spring 容器的直接依赖。</p>
 */
public interface ProxyPoolAccessor {

    /**
     * 代理池是否已启用（开关打开且至少配置了一个代理）。
     */
    boolean isEnabled();

    /**
     * 轮询获取一个可用代理（跳过黑名单）。
     *
     * @return 可用代理；全部在黑名单中或池为空时返回 {@code null}
     */
    Proxy acquireProxy();

    /**
     * 标记一次代理调用成功（将其移出黑名单）。
     */
    void markSuccess(Proxy proxy);

    /**
     * 标记一次代理调用失败（加入黑名单，带 TTL）。
     *
     * @param proxy  失败的代理
     * @param reason 失败原因（用于日志，可为 {@code null}）
     */
    void markFailure(Proxy proxy, String reason);

    /**
     * 单次请求最大尝试次数（含首次）。
     */
    int maxAttempts();
}
