package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.risk.dto.RiskAutomationStatus;

import java.util.Map;

/**
 * 风控检测与账号级自动化保护服务
 * 参考Python代码的风控处理逻辑
 */
public interface RiskControlService {

    /**
     * 检测API响应是否触发风控
     *
     * @param response API响应数据
     * @return true=触发风控，false=正常
     */
    boolean detectRiskControl(Map<String, Object> response);

    /**
     * 处理风控情况
     *
     * @param accountId 账号ID
     * @param response API响应数据
     * @return 处理结果
     */
    RiskControlResult handleRiskControl(Long accountId, Map<String, Object> response);

    /**
     * 检测Token是否失效
     *
     * @param response API响应数据
     * @return true=Token失效，false=正常
     */
    boolean isTokenExpired(Map<String, Object> response);

    // ==================== P0-6 账号级自动化保护 ====================

    /**
     * 账号是否允许继续执行自动化操作（手动暂停、认证/风控冷却均视为不允许）。
     *
     * @param accountId 账号ID
     * @return true=允许，false=暂停/冷却中
     */
    boolean isAutomationAllowed(Long accountId);

    /**
     * 记录一次风控/限流事件（持久化到风险事件表，供审计与前端展示）。
     *
     * @param accountId 账号ID
     * @param eventType 事件类型（RISK_CONTROL / RATE_LIMITED / MANUAL_PAUSE / RESUME）
     * @param reason    触发原因
     */
    void recordRiskEvent(Long accountId, String eventType, String reason);

    /**
     * 手动暂停账号自动化（持久化，重启后仍生效）。
     *
     * @param accountId 账号ID
     * @param reason    暂停原因
     */
    void pauseAutomation(Long accountId, String reason);

    /**
     * 手动恢复账号自动化（同时清除认证/风控冷却状态）。
     *
     * @param accountId 账号ID
     */
    void resumeAutomation(Long accountId);

    /**
     * 查询账号自动化状态（暂停状态、冷却剩余时间）。
     *
     * @param accountId 账号ID
     * @return 自动化状态
     */
    RiskAutomationStatus getAutomationStatus(Long accountId);

    /**
     * 风控处理结果
     */
    enum RiskControlResult {
        /**
         * 正常，无风控
         */
        NORMAL,

        /**
         * 触发风控，需要更新Cookie
         */
        RISK_CONTROL_DETECTED,

        /**
         * Token失效，需要刷新Token
         */
        TOKEN_EXPIRED,

        /**
         * 限流，需要等待
         */
        RATE_LIMITED
    }
}
