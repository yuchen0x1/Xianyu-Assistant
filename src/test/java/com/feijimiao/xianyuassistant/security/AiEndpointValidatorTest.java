package com.feijimiao.xianyuassistant.security;

import com.feijimiao.xianyuassistant.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI端点安全校验器测试。
 * 只使用本机可解析的地址（字面量IP/localhost），避免测试依赖外部 DNS。
 */
class AiEndpointValidatorTest {

    @Test
    void blankOrEmptyPasses() {
        AiEndpointValidator validator = new AiEndpointValidator("");
        assertDoesNotThrow(() -> validator.validate(null));
        assertDoesNotThrow(() -> validator.validate(""));
        assertDoesNotThrow(() -> validator.validate("   "));
    }

    @Test
    void whitelistedHostAcceptedEvenWithHttp() {
        AiEndpointValidator validator = new AiEndpointValidator("127.0.0.1,dashscope.aliyuncs.com");
        // 白名单主机豁免 https 与公网校验（本地 ollama 场景），且不触发 DNS
        assertDoesNotThrow(() -> validator.validate("http://127.0.0.1:11434/v1"));
        assertDoesNotThrow(() -> validator.validate("https://dashscope.aliyuncs.com/compatible-mode"));
    }

    @Test
    void httpsPublicHostAcceptanceWithoutDnsViaWhitelist() {
        // 非白名单公网域名会做真实解析，测试环境不依赖外网：仅验证校验器对白名单匹配逻辑
        AiEndpointValidator validator = new AiEndpointValidator("dashscope.aliyuncs.com");
        assertDoesNotThrow(() -> validator.validate("https://dashscope.aliyuncs.com/compatible-mode/v1"));
    }

    @Test
    void rejectsHttpForNonWhitelisted() {
        AiEndpointValidator validator = new AiEndpointValidator("");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate("http://example.com/v1"));
        assertTrue(ex.getMessage().contains("https"));
    }

    @Test
    void rejectsLoopbackLiteralIp() {
        AiEndpointValidator validator = new AiEndpointValidator("");
        assertThrows(BusinessException.class, () -> validator.validate("https://127.0.0.1:8080/v1"));
    }

    @Test
    void rejectsPrivateRanges() {
        AiEndpointValidator validator = new AiEndpointValidator("");
        assertThrows(BusinessException.class, () -> validator.validate("https://192.168.1.10/v1"));
        assertThrows(BusinessException.class, () -> validator.validate("https://10.0.0.5/v1"));
        assertThrows(BusinessException.class, () -> validator.validate("https://172.16.0.9/v1"));
        assertThrows(BusinessException.class, () -> validator.validate("https://100.64.0.1/v1"));
    }

    @Test
    void rejectsLinkLocalAndMetadata() {
        AiEndpointValidator validator = new AiEndpointValidator("");
        assertThrows(BusinessException.class, () -> validator.validate("https://169.254.169.254/latest"));
        assertThrows(BusinessException.class, () -> validator.validate("https://0.0.0.0/v1"));
    }

    @Test
    void rejectsIPv6LoopbackAndV4Mapped() {
        AiEndpointValidator validator = new AiEndpointValidator("");
        assertThrows(BusinessException.class, () -> validator.validate("https://[::1]:8080/v1"));
        assertThrows(BusinessException.class, () -> validator.validate("https://[::ffff:127.0.0.1]/v1"));
    }

    @Test
    void rejectsLocalhostHostname() {
        AiEndpointValidator validator = new AiEndpointValidator("");
        assertThrows(BusinessException.class, () -> validator.validate("https://localhost/v1"));
    }

    @Test
    void rejectsMalformedUrls() {
        AiEndpointValidator validator = new AiEndpointValidator("");
        assertThrows(BusinessException.class, () -> validator.validate("not-a-url"));
        assertThrows(BusinessException.class, () -> validator.validate("ftp://example.com"));
        assertThrows(BusinessException.class, () -> validator.validate("https://user:pass@example.com/v1"));
    }

    @Test
    void whitelistMatchingIgnoresCase() {
        AiEndpointValidator validator = new AiEndpointValidator("DASHSCOPE.aliyuncs.com");
        assertDoesNotThrow(() -> validator.validate("https://DASHSCOPE.aliyuncs.com/compatible-mode"));
    }
}
