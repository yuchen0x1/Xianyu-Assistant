package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.cache.LocalMapCacheServiceImpl;
import com.feijimiao.xianyuassistant.entity.SysLoginToken;
import com.feijimiao.xianyuassistant.entity.SysUser;
import com.feijimiao.xianyuassistant.mapper.SysLoginTokenMapper;
import com.feijimiao.xianyuassistant.mapper.SysUserMapper;
import com.feijimiao.xianyuassistant.service.AuthService;
import com.feijimiao.xianyuassistant.service.bo.RegisterReqBO;
import com.feijimiao.xianyuassistant.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceImplRegistrationTransactionTest {

    @TempDir
    Path tempDir;

    @Test
    void failedSessionCreationRollsBackTheUserAndAllowsRegistrationRetry() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("auth-transaction.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE test_user (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT, password TEXT, status INTEGER)");
        jdbc.execute("CREATE TABLE test_token (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, token TEXT)");

        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysLoginTokenMapper tokenMapper = mock(SysLoginTokenMapper.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        LocalMapCacheServiceImpl cache = new LocalMapCacheServiceImpl();
        AtomicBoolean failTokenInsert = new AtomicBoolean(true);

        when(userMapper.selectCount(isNull())).thenAnswer(invocation ->
                jdbc.queryForObject("SELECT COUNT(*) FROM test_user", Long.class));
        when(userMapper.selectOne(any())).thenAnswer(invocation -> jdbc.query(
                "SELECT id, username, password, status FROM test_user LIMIT 1",
                (rs, rowNum) -> {
                    SysUser user = new SysUser();
                    user.setId(rs.getLong("id"));
                    user.setUsername(rs.getString("username"));
                    user.setPassword(rs.getString("password"));
                    user.setStatus(rs.getInt("status"));
                    return user;
                }).stream().findFirst().orElse(null));
        doAnswer(invocation -> {
            SysUser user = invocation.getArgument(0);
            jdbc.update("INSERT INTO test_user(username, password, status) VALUES (?, ?, ?)",
                    user.getUsername(), user.getPassword(), user.getStatus());
            user.setId(jdbc.queryForObject(
                    "SELECT id FROM test_user WHERE username = ?", Long.class, user.getUsername()));
            return 1;
        }).when(userMapper).insert(any(SysUser.class));
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);

        when(tokenMapper.selectList(any())).thenReturn(List.of());
        when(tokenMapper.delete(any())).thenReturn(0);
        doAnswer(invocation -> {
            if (failTokenInsert.get()) {
                throw new IllegalStateException("token insert failed");
            }
            SysLoginToken token = invocation.getArgument(0);
            return jdbc.update("INSERT INTO test_token(user_id, token) VALUES (?, ?)",
                    token.getUserId(), token.getToken());
        }).when(tokenMapper).insert(any(SysLoginToken.class));
        when(jwtUtil.generateToken(any(), any())).thenReturn("transaction-test-token");

        AuthService target = proxiedService(userMapper, tokenMapper, jwtUtil, cache, dataSource);
        RegisterReqBO request = new RegisterReqBO();
        request.setUsername("admin");
        request.setPassword("strong-password");

        assertThatThrownBy(() -> target.register(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token insert failed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM test_user", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM test_token", Integer.class)).isZero();
        assertThat(cache.get("token:transaction-test-token")).isNull();

        failTokenInsert.set(false);
        var response = target.register(request);

        assertThat(response.getToken()).isEqualTo("transaction-test-token");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM test_user", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM test_token", Integer.class)).isEqualTo(1);
        assertThat(cache.get("token:transaction-test-token")).isNotNull();
    }

    private AuthService proxiedService(SysUserMapper userMapper,
                                       SysLoginTokenMapper tokenMapper,
                                       JwtUtil jwtUtil,
                                       LocalMapCacheServiceImpl cache,
                                       DriverManagerDataSource dataSource) {
        AuthServiceImpl service = new AuthServiceImpl();
        ReflectionTestUtils.setField(service, "sysUserMapper", userMapper);
        ReflectionTestUtils.setField(service, "sysLoginTokenMapper", tokenMapper);
        ReflectionTestUtils.setField(service, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(service, "cacheService", cache);

        TransactionInterceptor transactionAdvice = new TransactionInterceptor();
        transactionAdvice.setTransactionManager(new DataSourceTransactionManager(dataSource));
        transactionAdvice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());

        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(transactionAdvice);
        return (AuthService) proxyFactory.getProxy();
    }
}
