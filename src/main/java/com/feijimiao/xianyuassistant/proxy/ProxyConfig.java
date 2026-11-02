package com.feijimiao.xianyuassistant.proxy;

import lombok.Data;

import java.net.InetSocketAddress;
import java.net.Proxy;

/**
 * 单个代理配置（POJO）。
 *
 * <p>配置存储在 {@code xianyu_sys_setting.proxy_list} 中，以 JSON 数组形式保存。
 * 字段保持精简，仅支持 HTTP / SOCKS 两种类型与可选的用户名密码认证。</p>
 */
@Data
public class ProxyConfig {

    /** 代理主机（IP 或域名） */
    private String host;

    /** 代理端口 */
    private int port;

    /** 代理类型：HTTP / SOCKS，默认 HTTP */
    private String type = "HTTP";

    /** 可选：代理认证用户名 */
    private String username;

    /** 可选：代理认证密码 */
    private String password;

    /**
     * 用于黑名单 key 的唯一标识（host:port）。
     */
    public String key() {
        return (host == null ? "" : host) + ":" + port;
    }

    /**
     * 转换为 JDK {@link Proxy}。
     */
    public Proxy toJavaProxy() {
        Proxy.Type t = (type != null && type.equalsIgnoreCase("SOCKS")) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        return new Proxy(t, new InetSocketAddress(host, port));
    }

    /**
     * 是否携带认证信息。
     */
    public boolean hasAuth() {
        return username != null && !username.isEmpty();
    }
}
