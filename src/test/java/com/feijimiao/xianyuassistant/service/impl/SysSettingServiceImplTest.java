package com.feijimiao.xianyuassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.config.rag.DynamicAIChatClientManager;
import com.feijimiao.xianyuassistant.entity.XianyuSysSetting;
import com.feijimiao.xianyuassistant.mapper.XianyuSysSettingMapper;
import com.feijimiao.xianyuassistant.proxy.ProxyPoolManager;
import com.feijimiao.xianyuassistant.security.CredentialCipher;
import com.feijimiao.xianyuassistant.service.bo.GetSettingReqBO;
import com.feijimiao.xianyuassistant.service.bo.GetSettingRespBO;
import com.feijimiao.xianyuassistant.service.bo.SaveSettingReqBO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysSettingServiceImplTest {

    @Mock
    private XianyuSysSettingMapper sysSettingMapper;
    @Mock
    private DynamicAIChatClientManager dynamicAIChatClientManager;
    @Mock
    private ProxyPoolManager proxyPoolManager;

    private SysSettingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SysSettingServiceImpl();
        ReflectionTestUtils.setField(service, "sysSettingMapper", sysSettingMapper);
        ReflectionTestUtils.setField(service, "dynamicAIChatClientManager", dynamicAIChatClientManager);
        ReflectionTestUtils.setField(service, "proxyPoolManager", proxyPoolManager);
    }

    private XianyuSysSetting setting(String key, String value) {
        XianyuSysSetting s = new XianyuSysSetting();
        s.setSettingKey(key);
        s.setSettingValue(value);
        return s;
    }

    @Test
    void getSettingMasksSensitiveValueAndNeverReturnsPlaintext() {
        when(sysSettingMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(setting("ai_api_key", "sk-plaintext"));
        GetSettingReqBO req = new GetSettingReqBO();
        req.setSettingKey("ai_api_key");

        GetSettingRespBO resp = service.getSetting(req);

        assertThat(resp.getSettingValue()).isEqualTo("********");
        assertThat(resp.getHasSecret()).isTrue();
        assertThat(resp.getSettingValue()).doesNotContain("sk-plaintext");
    }

    @Test
    void getAllSettingsMasksSensitiveKeysAndKeepsNormalValues() {
        when(sysSettingMapper.selectList(any())).thenReturn(List.of(
                setting("ai_api_key", "secret-1"),
                setting("app_port", "12400")));

        List<GetSettingRespBO> list = service.getAllSettings();

        GetSettingRespBO sensitive = list.stream()
                .filter(b -> "ai_api_key".equals(b.getSettingKey())).findFirst().orElseThrow();
        GetSettingRespBO normal = list.stream()
                .filter(b -> "app_port".equals(b.getSettingKey())).findFirst().orElseThrow();
        assertThat(sensitive.getSettingValue()).isEqualTo("********");
        assertThat(sensitive.getHasSecret()).isTrue();
        assertThat(normal.getSettingValue()).isEqualTo("12400");
        assertThat(normal.getHasSecret()).isFalse();
    }

    @Test
    void saveSensitiveWithMaskedValueKeepsExisting() {
        when(sysSettingMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(setting("notify_dingtalk_secret", "old-secret"));

        SaveSettingReqBO req = new SaveSettingReqBO();
        req.setSettingKey("notify_dingtalk_secret");
        req.setSettingValue("********");
        service.saveSetting(req);

        ArgumentCaptor<XianyuSysSetting> captor = ArgumentCaptor.forClass(XianyuSysSetting.class);
        verify(sysSettingMapper).updateById(captor.capture());
        assertThat(captor.getValue().getSettingValue()).isEqualTo("old-secret");
    }

    @Test
    void saveSensitiveWithBlankValueKeepsExisting() {
        when(sysSettingMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(setting("notify_ntfy_token", "old-token"));

        SaveSettingReqBO req = new SaveSettingReqBO();
        req.setSettingKey("notify_ntfy_token");
        req.setSettingValue("   ");
        service.saveSetting(req);

        ArgumentCaptor<XianyuSysSetting> captor = ArgumentCaptor.forClass(XianyuSysSetting.class);
        verify(sysSettingMapper).updateById(captor.capture());
        assertThat(captor.getValue().getSettingValue()).isEqualTo("old-token");
    }

    @Test
    void saveSensitiveWithClearSecretExplicitlyClears() {
        when(sysSettingMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(setting("ai_api_key", "old-key"));

        SaveSettingReqBO req = new SaveSettingReqBO();
        req.setSettingKey("ai_api_key");
        req.setSettingValue("********");
        req.setClearSecret(true);
        service.saveSetting(req);

        ArgumentCaptor<XianyuSysSetting> captor = ArgumentCaptor.forClass(XianyuSysSetting.class);
        verify(sysSettingMapper).updateById(captor.capture());
        assertThat(captor.getValue().getSettingValue()).isNull();
    }

    @Test
    void saveSensitiveWithNewValueOverwrites() {
        when(sysSettingMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(setting("ai_api_key", "old-key"));

        SaveSettingReqBO req = new SaveSettingReqBO();
        req.setSettingKey("ai_api_key");
        req.setSettingValue("new-secret");
        service.saveSetting(req);

        ArgumentCaptor<XianyuSysSetting> captor = ArgumentCaptor.forClass(XianyuSysSetting.class);
        verify(sysSettingMapper).updateById(captor.capture());
        String stored = captor.getValue().getSettingValue();
        assertThat(stored).startsWith(CredentialCipher.PREFIX);
        assertThat(CredentialCipher.decrypt(stored)).isEqualTo("new-secret");
    }

    // ==================== 敏感配置落库加密 ====================

    @Test
    void saveSensitiveValuePersistsAsAesGcmCiphertext() {
        when(sysSettingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        SaveSettingReqBO req = new SaveSettingReqBO();
        req.setSettingKey("ai_api_key");
        req.setSettingValue("sk-real-secret");
        service.saveSetting(req);

        ArgumentCaptor<XianyuSysSetting> captor = ArgumentCaptor.forClass(XianyuSysSetting.class);
        verify(sysSettingMapper).insert(captor.capture());
        String stored = captor.getValue().getSettingValue();
        assertThat(stored).startsWith(CredentialCipher.PREFIX);
        assertThat(stored).doesNotContain("sk-real-secret");
        assertThat(CredentialCipher.decrypt(stored)).isEqualTo("sk-real-secret");
    }

    @Test
    void saveSensitiveValueIncludingProxyListPersistsAsCiphertext() {
        when(sysSettingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        SaveSettingReqBO req = new SaveSettingReqBO();
        req.setSettingKey("proxy_list");
        req.setSettingValue("http://user:pass@192.168.1.10:8080");
        service.saveSetting(req);

        ArgumentCaptor<XianyuSysSetting> captor = ArgumentCaptor.forClass(XianyuSysSetting.class);
        verify(sysSettingMapper).insert(captor.capture());
        String stored = captor.getValue().getSettingValue();
        assertThat(stored).startsWith(CredentialCipher.PREFIX);
        assertThat(stored).doesNotContain("pass");
        assertThat(CredentialCipher.decrypt(stored))
                .isEqualTo("http://user:pass@192.168.1.10:8080");
    }

    @Test
    void saveNonSensitiveValuePersistsPlaintextUnchanged() {
        when(sysSettingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        SaveSettingReqBO req = new SaveSettingReqBO();
        req.setSettingKey("app_port");
        req.setSettingValue("12400");
        service.saveSetting(req);

        ArgumentCaptor<XianyuSysSetting> captor = ArgumentCaptor.forClass(XianyuSysSetting.class);
        verify(sysSettingMapper).insert(captor.capture());
        assertThat(captor.getValue().getSettingValue()).isEqualTo("12400");
    }

    @Test
    void getSettingValueDecryptsStoredCiphertext() {
        String plaintext = "sk-real-secret";
        when(sysSettingMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(setting("ai_api_key", CredentialCipher.encrypt(plaintext)));

        assertThat(service.getSettingValue("ai_api_key")).isEqualTo(plaintext);
    }

    @Test
    void getSettingValueKeepsLegacyPlaintextCompatible() {
        when(sysSettingMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(setting("ai_api_key", "legacy-plain-secret"));

        assertThat(service.getSettingValue("ai_api_key")).isEqualTo("legacy-plain-secret");
    }

    @Test
    void saveSensitiveMaskedRollbackKeepsStoredCiphertext() {
        String storedCipher = CredentialCipher.encrypt("kept-secret");
        when(sysSettingMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(setting("notify_dingtalk_secret", storedCipher));

        SaveSettingReqBO req = new SaveSettingReqBO();
        req.setSettingKey("notify_dingtalk_secret");
        req.setSettingValue("********");
        service.saveSetting(req);

        ArgumentCaptor<XianyuSysSetting> captor = ArgumentCaptor.forClass(XianyuSysSetting.class);
        verify(sysSettingMapper).updateById(captor.capture());
        assertThat(captor.getValue().getSettingValue()).isEqualTo(storedCipher);
        assertThat(CredentialCipher.decrypt(captor.getValue().getSettingValue())).isEqualTo("kept-secret");
    }

    @Test
    void getSettingMaskResponseNeverExposesStoredCiphertextOrPlaintext() {
        String plaintext = "sk-real-secret";
        when(sysSettingMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(setting("ai_api_key", CredentialCipher.encrypt(plaintext)));
        GetSettingReqBO req = new GetSettingReqBO();
        req.setSettingKey("ai_api_key");

        GetSettingRespBO resp = service.getSetting(req);

        assertThat(resp.getSettingValue()).isEqualTo("********");
        assertThat(resp.getHasSecret()).isTrue();
        assertThat(resp.getSettingValue()).doesNotContain("sk-real-secret")
                .doesNotContain(CredentialCipher.PREFIX);
    }

    @Test
    void getAllSettingsMasksEncryptedSensitiveAndKeepsNormalValues() {
        when(sysSettingMapper.selectList(any())).thenReturn(List.of(
                setting("ai_api_key", CredentialCipher.encrypt("secret-1")),
                setting("email_smtp_password", CredentialCipher.encrypt("smtp-pwd")),
                setting("app_port", "12400")));

        List<GetSettingRespBO> list = service.getAllSettings();

        GetSettingRespBO sensitive = list.stream()
                .filter(b -> "ai_api_key".equals(b.getSettingKey())).findFirst().orElseThrow();
        GetSettingRespBO normal = list.stream()
                .filter(b -> "app_port".equals(b.getSettingKey())).findFirst().orElseThrow();
        assertThat(sensitive.getSettingValue()).isEqualTo("********");
        assertThat(sensitive.getHasSecret()).isTrue();
        assertThat(sensitive.getSettingValue()).doesNotContain(CredentialCipher.PREFIX);
        assertThat(normal.getSettingValue()).isEqualTo("12400");
        assertThat(normal.getHasSecret()).isFalse();
    }
}
