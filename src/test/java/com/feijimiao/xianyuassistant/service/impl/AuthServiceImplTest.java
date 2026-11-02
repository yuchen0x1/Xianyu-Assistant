package com.feijimiao.xianyuassistant.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.feijimiao.xianyuassistant.cache.LocalMapCacheServiceImpl;
import com.feijimiao.xianyuassistant.entity.SysLoginToken;
import com.feijimiao.xianyuassistant.entity.SysUser;
import com.feijimiao.xianyuassistant.mapper.SysLoginTokenMapper;
import com.feijimiao.xianyuassistant.mapper.SysUserMapper;
import com.feijimiao.xianyuassistant.service.bo.ChangePasswordReqBO;
import com.feijimiao.xianyuassistant.service.bo.LoginReqBO;
import com.feijimiao.xianyuassistant.service.bo.LoginRespBO;
import com.feijimiao.xianyuassistant.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final Long USER_ID = 7L;
    private static final String OLD_TOKEN = "old-token-that-must-be-revoked";

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private SysLoginTokenMapper sysLoginTokenMapper;

    @Mock
    private JwtUtil jwtUtil;

    private LocalMapCacheServiceImpl cacheService;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        cacheService = new LocalMapCacheServiceImpl();
        authService = new AuthServiceImpl();
        ReflectionTestUtils.setField(authService, "sysUserMapper", sysUserMapper);
        ReflectionTestUtils.setField(authService, "sysLoginTokenMapper", sysLoginTokenMapper);
        ReflectionTestUtils.setField(authService, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(authService, "cacheService", cacheService);
    }

    @Test
    void loginRevokesPreviouslyCachedTokens() {
        SysUser user = userWithPassword("old-password");
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(sysLoginTokenMapper.selectList(any())).thenReturn(List.of(loginToken(OLD_TOKEN)));
        when(jwtUtil.generateToken(USER_ID, "admin")).thenReturn("new-token");
        cacheService.put("token:" + OLD_TOKEN, USER_ID);

        LoginReqBO request = new LoginReqBO();
        request.setUsername("admin");
        request.setPassword("old-password");
        authService.login(request);

        assertThat(authService.isTokenValid(OLD_TOKEN)).isFalse();
        assertThat(cacheService.get("token:new-token")).isEqualTo(USER_ID);
    }

    @Test
    void loginOnlyChangesTheCacheAfterTheDatabaseTransactionCommits() {
        SysUser user = userWithPassword("old-password");
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(sysLoginTokenMapper.selectList(any())).thenReturn(List.of(loginToken(OLD_TOKEN)));
        when(jwtUtil.generateToken(USER_ID, "admin")).thenReturn("new-token");
        cacheService.put("token:" + OLD_TOKEN, USER_ID);
        LoginReqBO request = new LoginReqBO();
        request.setUsername("admin");
        request.setPassword("old-password");

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            authService.login(request);

            assertThat(cacheService.get("token:" + OLD_TOKEN)).isEqualTo(USER_ID);
            assertThat(cacheService.get("token:new-token")).isNull();

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            assertThat(cacheService.get("token:" + OLD_TOKEN)).isNull();
            assertThat(cacheService.get("token:new-token")).isEqualTo(USER_ID);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void changePasswordRevokesEveryCachedTokenForTheUser() {
        SysUser user = userWithPassword("old-password");
        when(sysUserMapper.selectById(USER_ID)).thenReturn(user);
        when(sysLoginTokenMapper.selectList(any())).thenReturn(List.of(
                loginToken(OLD_TOKEN),
                loginToken("second-old-token")
        ));
        cacheService.put("token:" + OLD_TOKEN, USER_ID);
        cacheService.put("token:second-old-token", USER_ID);

        ChangePasswordReqBO request = new ChangePasswordReqBO();
        request.setUserId(USER_ID);
        request.setOldPassword("old-password");
        request.setNewPassword("new-password");
        authService.changePassword(request);

        assertThat(authService.isTokenValid(OLD_TOKEN)).isFalse();
        assertThat(authService.isTokenValid("second-old-token")).isFalse();
    }

    @Test
    void logoutDoesNotWriteTheRawTokenToLogs() {
        String sensitiveToken = "header.payload.signature-sensitive-value";
        Logger logger = (Logger) LoggerFactory.getLogger(AuthServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            authService.logout(sensitiveToken);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains(sensitiveToken));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void refreshTokenRotatesTheTokenAndRevokesTheOldOne() {
        when(jwtUtil.validateToken(OLD_TOKEN)).thenReturn(true);
        when(jwtUtil.getUserIdFromToken(OLD_TOKEN)).thenReturn(USER_ID);
        when(jwtUtil.getUsernameFromToken(OLD_TOKEN)).thenReturn("admin");
        when(jwtUtil.generateToken(USER_ID, "admin")).thenReturn("refreshed-token");
        when(sysUserMapper.selectById(USER_ID)).thenReturn(userWithPassword("whatever"));
        when(sysLoginTokenMapper.selectList(any())).thenReturn(List.of(loginToken(OLD_TOKEN)));
        cacheService.put("token:" + OLD_TOKEN, USER_ID);

        LoginRespBO response = authService.refreshToken(OLD_TOKEN);

        assertThat(response.getToken()).isEqualTo("refreshed-token");
        assertThat(response.getUsername()).isEqualTo("admin");
        assertThat(authService.isTokenValid(OLD_TOKEN)).isFalse();
        assertThat(cacheService.get("token:refreshed-token")).isEqualTo(USER_ID);
    }

    @Test
    void refreshTokenRejectsInvalidOrExpiredToken() {
        when(jwtUtil.validateToken("expired-token")).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> authService.refreshToken("expired-token"))
                .isInstanceOf(com.feijimiao.xianyuassistant.exception.BusinessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> authService.refreshToken(null))
                .isInstanceOf(com.feijimiao.xianyuassistant.exception.BusinessException.class);
    }

    @Test
    void refreshTokenRejectsRevokedSession() {
        when(jwtUtil.validateToken(OLD_TOKEN)).thenReturn(true);
        // 会话不在缓存也不在库中（已被登出/换设备撤销）
        when(sysLoginTokenMapper.selectOne(any())).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> authService.refreshToken(OLD_TOKEN))
                .isInstanceOf(com.feijimiao.xianyuassistant.exception.BusinessException.class);
    }

    @Test
    void attemptLimitsAreScopedPerPurpose() {
        for (int i = 0; i < 5; i++) {
            authService.recordFailure("1.2.3.4", "login");
        }
        // 严格档位（login/register）：5 次失败后封禁
        assertThat(authService.checkAttempt("1.2.3.4", "login")).isFalse();

        // 宽松档位（check）：同样 5 次失败不影响
        assertThat(authService.checkAttempt("1.2.3.4", "check")).isTrue();

        // 不同 IP 互不影响
        assertThat(authService.checkAttempt("5.6.7.8", "login")).isTrue();

        // 成功后清除
        authService.clearFailure("1.2.3.4", "login");
        assertThat(authService.checkAttempt("1.2.3.4", "login")).isTrue();
    }

    private SysUser userWithPassword(String rawPassword) {
        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setUsername("admin");
        user.setPassword(new BCryptPasswordEncoder().encode(rawPassword));
        user.setStatus(1);
        return user;
    }

    private SysLoginToken loginToken(String token) {
        SysLoginToken loginToken = new SysLoginToken();
        loginToken.setUserId(USER_ID);
        loginToken.setToken(token);
        return loginToken;
    }
}
