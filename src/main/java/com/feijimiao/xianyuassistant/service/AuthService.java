package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.service.bo.*;

/**
 * 认证服务接口
 * @author IAMLZY
 * @date 2026/4/22
 */
public interface AuthService {

    /**
     * 检查是否有用户存在（决定显示登录还是注册）
     */
    CheckUserExistsRespBO checkUserExists();

    /**
     * 注册
     */
    LoginRespBO register(RegisterReqBO reqBO);

    /**
     * 登录
     */
    LoginRespBO login(LoginReqBO reqBO);

    /**
     * 验证Token是否有效（在数据库中且未过期）
     */
    boolean isTokenValid(String token);

    /**
     * 刷新会话：校验当前有效 Token 后轮换签发新 Token（旧 Token 立即撤销，单设备语义不变）。
     *
     * @param token 当前持有且签名有效、未过期、会话存在的 Token
     * @return 新 Token 与用户名
     * @throws com.feijimiao.xianyuassistant.exception.BusinessException Token 无效/过期/会话不存在
     */
    LoginRespBO refreshToken(String token);

    /**
     * 检查指定范围的失败次数限制（scope: login/register/check）
     * @return true=允许 false=超过限制
     */
    boolean checkAttempt(String ip, String scope);

    /**
     * 记录指定范围的失败次数
     */
    void recordFailure(String ip, String scope);

    /**
     * 清除指定范围的失败记录（成功后调用）
     */
    void clearFailure(String ip, String scope);

    /**
     * 获取当前用户信息
     */
    com.feijimiao.xianyuassistant.entity.SysUser getCurrentUser(Long userId);

    /**
     * 修改密码
     */
    void changePassword(ChangePasswordReqBO reqBO);

    /**
     * 退出登录
     */
    void logout(String token);
}
