package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.exception.BusinessException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.cache.CacheService;
import com.feijimiao.xianyuassistant.entity.SysLoginToken;
import com.feijimiao.xianyuassistant.entity.SysUser;
import com.feijimiao.xianyuassistant.mapper.SysLoginTokenMapper;
import com.feijimiao.xianyuassistant.mapper.SysUserMapper;
import com.feijimiao.xianyuassistant.rbac.entity.SysRole;
import com.feijimiao.xianyuassistant.rbac.entity.SysUserRole;
import com.feijimiao.xianyuassistant.rbac.mapper.SysRoleMapper;
import com.feijimiao.xianyuassistant.rbac.mapper.SysUserRoleMapper;
import com.feijimiao.xianyuassistant.service.AuthService;
import com.feijimiao.xianyuassistant.service.bo.*;
import com.feijimiao.xianyuassistant.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务实现
 * @author IAMLZY
 * @date 2026/4/22
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 登录错误限制：10分钟窗口内 login/register 最多5次、check 最多60次 */
    private static final int MAX_ATTEMPTS_STRICT = 5;
    private static final int MAX_ATTEMPTS_LOOSE = 60;
    private static final long ATTEMPT_WINDOW_MS = 10 * 60 * 1000L;

    /** 缓存key前缀：login_attempt:<scope>:<ip> */
    private static final String ATTEMPT_PREFIX = "login_attempt:";

    /** 会话有效期（天），与 jwt.expiration 保持一致 */
    private static final long SESSION_TTL_DAYS = 7;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysLoginTokenMapper sysLoginTokenMapper;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private CacheService cacheService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public CheckUserExistsRespBO checkUserExists() {
        CheckUserExistsRespBO respBO = new CheckUserExistsRespBO();
        long count = sysUserMapper.selectCount(null);
        respBO.setExists(count > 0);
        return respBO;
    }

    /** 注册互斥锁：check-then-insert 必须串行，并发注册首个账号可产生多个 ADMIN */
    private static final Object REGISTER_LOCK = new Object();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginRespBO register(RegisterReqBO reqBO) {
        synchronized (REGISTER_LOCK) {
            return doRegister(reqBO);
        }
    }

    private LoginRespBO doRegister(RegisterReqBO reqBO) {
        // 检查是否已有用户
        long count = sysUserMapper.selectCount(null);
        if (count > 0) {
            throw new BusinessException("已有账号，无法注册");
        }

        // 检查用户名是否重复
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getUsername, reqBO.getUsername());
        SysUser existing = sysUserMapper.selectOne(wrapper);
        if (existing != null) {
            throw new BusinessException("用户名已存在");
        }

        // 创建用户
        SysUser user = new SysUser();
        user.setUsername(reqBO.getUsername());
        user.setPassword(passwordEncoder.encode(reqBO.getPassword()));
        user.setStatus(1);
        user.setCreatedTime(LocalDateTime.now());
        user.setUpdatedTime(LocalDateTime.now());
        sysUserMapper.insert(user);

        bindAdminRoleForFirstUser(user.getId());

        log.info("[Auth] 注册成功: userId={}", user.getId());

        // 注册后自动登录
        LoginReqBO loginReqBO = new LoginReqBO();
        loginReqBO.setUsername(reqBO.getUsername());
        loginReqBO.setPassword(reqBO.getPassword());
        return login(loginReqBO);
    }

    private void bindAdminRoleForFirstUser(Long userId) {
        if (sysRoleMapper == null || sysUserRoleMapper == null) {
            log.warn("[Auth] RBAC components are not ready; skip ADMIN role binding for first user: userId={}", userId);
            return;
        }
        SysRole adminRole = sysRoleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getName, "ADMIN"));
        if (adminRole == null) {
            log.warn("未找到ADMIN角色,userId=" + userId);
            return;
        }
        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(userId);
        userRole.setRoleId(adminRole.getId());
        userRole.setCreatedTime(LocalDateTime.now());
        sysUserRoleMapper.insert(userRole);
        log.info("首位用户已绑定ADMIN角色,userId=" + userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginRespBO login(LoginReqBO reqBO) {
        // 查找用户
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getUsername, reqBO.getUsername());
        SysUser user = sysUserMapper.selectOne(wrapper);

        if (user == null) {
            throw new BusinessException("用户名或密码错误");
        }

        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException("账号已被禁用");
        }

        // 验证密码
        if (!passwordEncoder.matches(reqBO.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        // 生成Token
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());

        // 单设备登录：同时撤销数据库和缓存中的旧Token
        revokeAllTokens(user.getId());

        // 保存新Token到数据库
        SysLoginToken loginToken = new SysLoginToken();
        loginToken.setUserId(user.getId());
        loginToken.setToken(token);
        loginToken.setDeviceId(reqBO.getDeviceId());
        loginToken.setLoginIp(reqBO.getIp());
        loginToken.setExpireTime(LocalDateTime.now().plusDays(SESSION_TTL_DAYS));
        loginToken.setCreatedTime(LocalDateTime.now());
        loginToken.setUpdatedTime(LocalDateTime.now());
        sysLoginTokenMapper.insert(loginToken);

        // 更新用户最后登录信息
        user.setLastLoginTime(LocalDateTime.now());
        user.setLastLoginIp(reqBO.getIp());
        sysUserMapper.updateById(user);

        // 仅在数据库事务提交后开放新Token，避免提交失败时缓存中残留幽灵会话
        afterCommit(() -> cacheService.put("token:" + token, user.getId(), SESSION_TTL_DAYS, TimeUnit.DAYS));

        log.info("[Auth] 登录成功: userId={}", user.getId());

        LoginRespBO respBO = new LoginRespBO();
        respBO.setToken(token);
        respBO.setUsername(user.getUsername());
        return respBO;
    }

    @Override
    public boolean isTokenValid(String token) {
        // 先查缓存
        Object cached = cacheService.get("token:" + token);
        if (cached != null) {
            return true;
        }

        // 缓存未命中，查数据库
        LambdaQueryWrapper<SysLoginToken> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysLoginToken::getToken, token);
        SysLoginToken loginToken = sysLoginTokenMapper.selectOne(wrapper);

        if (loginToken == null) {
            return false;
        }

        // 检查是否过期
        try {
            LocalDateTime expireTime = loginToken.getExpireTime();
            if (expireTime.isBefore(LocalDateTime.now())) {
                // Token已过期，删除
                sysLoginTokenMapper.deleteById(loginToken.getId());
                return false;
            }
        } catch (Exception e) {
            log.warn("[Auth] 解析Token过期时间失败: {}", loginToken.getExpireTime());
        }

        // 回填缓存
        cacheService.put("token:" + token, loginToken.getUserId(), SESSION_TTL_DAYS, TimeUnit.DAYS);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginRespBO refreshToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException("Token不能为空");
        }
        // 签名与有效期校验（拒绝已过期 Token 的刷新，必须重新登录）
        if (!jwtUtil.validateToken(token)) {
            throw new BusinessException("登录已过期，请重新登录");
        }
        // 会话必须仍然存在（未被他端登出/撤销）
        if (!isTokenValid(token)) {
            throw new BusinessException("会话已失效，请重新登录");
        }
        Long userId = jwtUtil.getUserIdFromToken(token);
        String username = jwtUtil.getUsernameFromToken(token);
        if (userId == null || username == null) {
            throw new BusinessException("Token内容无效");
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || (user.getStatus() != null && user.getStatus() == 0)) {
            throw new BusinessException("账号不可用，请重新登录");
        }

        // 轮换：撤销当前用户全部旧 Token，签发新 Token（单设备语义不变）
        String newToken = jwtUtil.generateToken(userId, username);
        revokeAllTokens(userId);

        SysLoginToken loginToken = new SysLoginToken();
        loginToken.setUserId(userId);
        loginToken.setToken(newToken);
        loginToken.setDeviceId("refresh");
        loginToken.setLoginIp(null);
        loginToken.setExpireTime(LocalDateTime.now().plusDays(SESSION_TTL_DAYS));
        loginToken.setCreatedTime(LocalDateTime.now());
        loginToken.setUpdatedTime(LocalDateTime.now());
        sysLoginTokenMapper.insert(loginToken);

        afterCommit(() -> cacheService.put("token:" + newToken, userId, SESSION_TTL_DAYS, TimeUnit.DAYS));

        log.info("[Auth] 会话刷新成功: userId={}", userId);
        LoginRespBO respBO = new LoginRespBO();
        respBO.setToken(newToken);
        respBO.setUsername(username);
        return respBO;
    }

    @Override
    public boolean checkAttempt(String ip, String scope) {
        String key = attemptKey(ip, scope);
        int maxAttempts = "check".equals(scope) ? MAX_ATTEMPTS_LOOSE : MAX_ATTEMPTS_STRICT;
        Object count = cacheService.get(key);
        if (count == null) {
            return true;
        }
        if (count instanceof Number) {
            return ((Number) count).intValue() < maxAttempts;
        }
        // AtomicLong类型
        try {
            long val = Long.parseLong(count.toString());
            return val < maxAttempts;
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    public void recordFailure(String ip, String scope) {
        String key = attemptKey(ip, scope);
        if (!cacheService.containsKey(key)) {
            // 首次失败，设置10分钟过期
            cacheService.put(key, cacheService.increment(key), ATTEMPT_WINDOW_MS, TimeUnit.MILLISECONDS);
        } else {
            cacheService.increment(key);
            // 确保过期时间存在
            if (cacheService.getExpire(key) == -1) {
                cacheService.expire(key, ATTEMPT_WINDOW_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public void clearFailure(String ip, String scope) {
        cacheService.remove(attemptKey(ip, scope));
    }

    private static String attemptKey(String ip, String scope) {
        return ATTEMPT_PREFIX + scope + ":" + ip;
    }

    @Override
    public SysUser getCurrentUser(Long userId) {
        return sysUserMapper.selectById(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(ChangePasswordReqBO reqBO) {
        SysUser user = sysUserMapper.selectById(reqBO.getUserId());
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (!passwordEncoder.matches(reqBO.getOldPassword(), user.getPassword())) {
            throw new BusinessException("原密码错误");
        }
        user.setPassword(passwordEncoder.encode(reqBO.getNewPassword()));
        user.setUpdatedTime(LocalDateTime.now());
        sysUserMapper.updateById(user);
        revokeAllTokens(user.getId());
        log.info("[Auth] 修改密码成功: userId={}", reqBO.getUserId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void logout(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }

        // 删除数据库中的Token
        LambdaQueryWrapper<SysLoginToken> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysLoginToken::getToken, token);
        sysLoginTokenMapper.delete(wrapper);

        // 数据库提交成功后再同步缓存状态
        afterCommit(() -> cacheService.remove("token:" + token));

        log.info("[Auth] 退出登录成功");
    }

    private void revokeAllTokens(Long userId) {
        LambdaQueryWrapper<SysLoginToken> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysLoginToken::getUserId, userId);
        List<SysLoginToken> loginTokens = sysLoginTokenMapper.selectList(wrapper);

        sysLoginTokenMapper.delete(wrapper);

        if (loginTokens == null) {
            return;
        }
        afterCommit(() -> {
            for (SysLoginToken loginToken : loginTokens) {
                String token = loginToken.getToken();
                if (token != null && !token.isBlank()) {
                    cacheService.remove("token:" + token);
                }
            }
        });
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}


