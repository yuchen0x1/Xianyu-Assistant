package com.feijimiao.xianyuassistant.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Cookie工具类。
 * <p>
 * 所有方法已委托给 {@link XianyuSignUtils}，此类仅保留以兼容现有引用。
 * 新代码请直接使用 {@link XianyuSignUtils}。
 * </p>
 */
@Slf4j
@Deprecated
public class CookieUtils {

    /**
     * 将Cookie字符串转换为Map
     * @param cookieStr Cookie字符串
     * @return Cookie Map
     */
    public static Map<String, String> parseCookies(String cookieStr) {
        return XianyuSignUtils.parseCookies(cookieStr);
    }

    /**
     * 将Cookie Map转换为字符串
     * @param cookies Cookie Map
     * @return Cookie字符串
     */
    public static String formatCookies(Map<String, String> cookies) {
        return XianyuSignUtils.formatCookies(cookies);
    }

    /**
     * 提取_m_h5_tk中的token部分
     * @param cookieStr Cookie字符串
     * @return token
     */
    public static String extractToken(String cookieStr) {
        return XianyuSignUtils.extractTokenFromString(cookieStr);
    }

    /**
     * 生成API签名
     * @param timestamp 时间戳
     * @param token token
     * @param data 数据
     * @return 签名
     */
    public static String generateSign(String timestamp, String token, String data) {
        return XianyuSignUtils.generateSign(timestamp, token, data);
    }
}
