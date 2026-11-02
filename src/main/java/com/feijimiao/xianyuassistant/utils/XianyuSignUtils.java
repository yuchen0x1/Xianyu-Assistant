package com.feijimiao.xianyuassistant.utils;

import com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * 闲鱼签名工具类
 * 提供Cookie解析、签名生成等核心功能。
 */
@Slf4j
public class XianyuSignUtils {

    /**
     * 将Cookie字符串转换为Map
     */
    public static Map<String, String> parseCookies(String cookiesStr) {
        Map<String, String> cookies = new HashMap<>();
        if (cookiesStr == null || cookiesStr.isEmpty()) {
            return cookies;
        }

        String[] cookieArray = cookiesStr.split("; ");
        for (String cookie : cookieArray) {
            if (cookie.contains("=")) {
                String[] parts = cookie.split("=", 2);
                cookies.put(parts[0].trim(), parts[1].trim());
            }
        }
        return cookies;
    }

    /**
     * 判断 Cookie 字符串是否包含建立 WebSocket 连接所需的关键字段。
     * <p>
     * 用于防止 hasLogin / Token 刷新等接口返回不完整的 Set-Cookie 时，
     * 把数据库中完整的登录 Cookie 覆盖成残缺版本（导致一直重连）。
     * </p>
     */
    public static boolean hasCriticalCookieFields(String cookieStr) {
        Map<String, String> cookies = parseCookies(cookieStr);
        return cookies.containsKey("unb") && cookies.containsKey("_m_h5_tk");
    }

    /**
     * 校验 _m_h5_tk 值是否为正常格式（形如 {@code 32位hex_13位时间戳}）。
     * <p>
     * 用于防止密文（AESGCM: 前缀）或损坏内容被当作 token 反复加密写回，
     * 造成 m_h5_tk 字段嵌套加密膨胀。
     * </p>
     */
    public static boolean isValidMh5tk(String value) {
        if (value == null || value.isBlank() || value.length() > 200
                || value.startsWith("AESGCM:")) {
            return false;
        }
        int underscore = value.indexOf('_');
        if (underscore <= 0 || underscore == value.length() - 1) {
            return false;
        }
        // 前后段仅允许字母、数字、下划线、连字符
        return value.matches("[0-9A-Za-z_\\-]+_[0-9A-Za-z_\\-]+");
    }

    /**
     * 生成签名
     *
     * @param timestamp 时间戳
     * @param token     token (_m_h5_tk的前半部分)
     * @param data      数据JSON字符串
     * @return 签名
     */
    public static String generateSign(String timestamp, String token, String data) {
        try {
            String msg = token + "&" + timestamp + "&" + XianyuProtocolProfile.H5_APP_KEY + "&" + data;
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(msg.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : messageDigest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("生成签名失败", e);
            return "";
        }
    }

    /**
     * 从Cookie中提取token
     */
    public static String extractToken(Map<String, String> cookies) {
        String mH5Tk = cookies.get("_m_h5_tk");
        if (mH5Tk != null && mH5Tk.contains("_")) {
            return mH5Tk.split("_")[0];
        }
        return "";
    }

    /**
     * 从Cookie字符串中直接提取token
     */
    public static String extractTokenFromString(String cookieStr) {
        return extractToken(parseCookies(cookieStr));
    }

    /**
     * 将Cookie Map转换为字符串格式
     */
    public static String formatCookies(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }
}
