package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.entity.XianyuCookie;
import com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper;
import com.feijimiao.xianyuassistant.security.CredentialCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 账号凭据读取回归测试。
 *
 * <p>验证服务读取边界能够兼容数据库直接返回的 AESGCM 密文，以及历史明文数据。</p>
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceImplCredentialReadTest {

    private static final Long ACCOUNT_ID = 7L;

    @Mock
    private XianyuCookieMapper cookieMapper;

    private AccountServiceImpl accountService;

    /**
     * 仅注入凭据读取所需的 Mapper，保持测试与 Spring 容器及真实数据库隔离。
     */
    @BeforeEach
    void setUp() {
        accountService = new AccountServiceImpl();
        ReflectionTestUtils.setField(accountService, "cookieMapper", cookieMapper);
    }

    /**
     * Mapper 返回 AESGCM Cookie 密文时，账号服务应向业务层返回明文 Cookie。
     */
    @Test
    void getCookieByAccountIdDecryptsAesGcmCiphertextReturnedByMapper() {
        String plaintext = "unb=2200657796354; _m_h5_tk=cookie-token_1700000000000";
        XianyuCookie cookie = cookieWithCookieText(CredentialCipher.encrypt(plaintext));
        when(cookieMapper.selectOne(any())).thenReturn(cookie);

        String result = accountService.getCookieByAccountId(ACCOUNT_ID);

        assertThat(result).isEqualTo(plaintext);
    }

    /**
     * Mapper 返回历史明文 Cookie 时，幂等解密应保持原值不变。
     */
    @Test
    void getCookieByAccountIdKeepsLegacyPlaintextUnchanged() {
        String plaintext = "unb=legacy-user; cookie2=legacy-cookie";
        XianyuCookie cookie = cookieWithCookieText(plaintext);
        when(cookieMapper.selectOne(any())).thenReturn(cookie);

        String result = accountService.getCookieByAccountId(ACCOUNT_ID);

        assertThat(result).isEqualTo(plaintext);
    }

    /**
     * Mapper 返回 AESGCM 格式的独立 m_h5_tk 时，账号服务应返回解密后的 Token。
     */
    @Test
    void getMh5tkByAccountIdDecryptsAesGcmCiphertextReturnedByMapper() {
        String plaintext = "mh5-token_1700000000000";
        XianyuCookie cookie = new XianyuCookie();
        cookie.setMH5Tk(CredentialCipher.encrypt(plaintext));
        when(cookieMapper.selectOne(any())).thenReturn(cookie);

        String result = accountService.getMh5tkByAccountId(ACCOUNT_ID);

        assertThat(result).isEqualTo(plaintext);
    }

    /**
     * 创建只包含 Cookie 文本的测试实体，减少各用例重复装配代码。
     *
     * @param cookieText Mapper 模拟返回的 Cookie 字段值
     * @return 用于账号服务读取测试的 Cookie 实体
     */
    private XianyuCookie cookieWithCookieText(String cookieText) {
        XianyuCookie cookie = new XianyuCookie();
        cookie.setCookieText(cookieText);
        return cookie;
    }
}
