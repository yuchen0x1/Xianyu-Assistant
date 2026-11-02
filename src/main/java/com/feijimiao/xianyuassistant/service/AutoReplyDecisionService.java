package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.entity.AutoReplyDecision;
import com.feijimiao.xianyuassistant.mapper.AutoReplyDecisionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 自动回复决策日志服务
 *
 * <p>只记录"有追溯价值"的跳过与失败事件：人工接管、商品未同步、无可用策略、策略未生成内容。
 * 开关关闭（用户主动选择）、消息去重等噪音事件不落库。</p>
 *
 * <p>决策日志属于旁路审计，任何写入失败只记录警告，绝不影响自动回复主流程。</p>
 */
@Slf4j
@Service
public class AutoReplyDecisionService {

    /**
     * 会话被人工接管，自动回复让位
     */
    public static final String REASON_HUMAN_TAKEOVER = "HUMAN_TAKEOVER";

    /**
     * 商品未同步到本地库，无法定位商品配置
     */
    public static final String REASON_GOODS_NOT_FOUND = "GOODS_NOT_FOUND";

    /**
     * 未命中任何回复策略
     */
    public static final String REASON_NO_REPLY_STRATEGY = "NO_REPLY_STRATEGY";

    /**
     * 策略已执行但未生成有效回复内容（含 AI 生成失败）
     */
    public static final String REASON_NO_REPLY_CONTENT = "NO_REPLY_CONTENT";

    /**
     * 同一会话同一原因的去重窗口（分钟）：
     * 人工接管会话内每条买家消息都会触发跳过，60 分钟内只落一条，避免刷表
     */
    private static final int DEDUPE_WINDOW_MINUTES = 60;

    /**
     * 触发消息内容落库截断长度，防止超长消息撑爆日志表
     */
    private static final int MAX_MESSAGE_LENGTH = 500;

    @Autowired
    private AutoReplyDecisionMapper decisionMapper;

    /**
     * 决策日志保留天数（0=不清理），每天 03:30 清理一次超期记录
     */
    @Value("${app.auto-reply.decision-retention-days:90}")
    private int decisionRetentionDays;

    /**
     * 每日清理超期决策日志（本地时区凌晨执行）
     */
    @Scheduled(cron = "0 30 3 * * *")
    public void purgeExpiredDecisions() {
        if (decisionRetentionDays <= 0) {
            return;
        }
        try {
            int deleted = decisionMapper.deleteOlderThan(decisionRetentionDays);
            if (deleted > 0) {
                log.info("已清理 {} 条超期（>{}天）自动回复决策日志", deleted, decisionRetentionDays);
            }
        } catch (Exception e) {
            log.warn("决策日志清理失败: errorType={}", e.getClass().getSimpleName());
        }
    }

    /**
     * 记录一条跳过/失败决策（带同会话同原因去重）。任何异常吞掉并仅记录警告。
     */
    public void recordSkip(Long accountId, String sId, String xyGoodsId, String buyerUserId,
                           String buyerMessage, String reasonCode) {
        recordSkip(accountId, sId, xyGoodsId, buyerUserId, buyerMessage, reasonCode, null);
    }

    /**
     * 记录一条跳过/失败决策（带同会话同原因去重与补充说明）。任何异常吞掉并仅记录警告。
     */
    public void recordSkip(Long accountId, String sId, String xyGoodsId, String buyerUserId,
                           String buyerMessage, String reasonCode, String detail) {
        try {
            if (sId != null && !sId.isBlank()
                    && decisionMapper.countRecent(accountId, sId, reasonCode, DEDUPE_WINDOW_MINUTES) > 0) {
                log.debug("决策日志去重跳过: account={}, sId={}, reason={}", accountId, sId, reasonCode);
                return;
            }
            AutoReplyDecision decision = new AutoReplyDecision();
            decision.setXianyuAccountId(accountId);
            decision.setSId(sId);
            decision.setXyGoodsId(xyGoodsId);
            decision.setBuyerUserId(buyerUserId);
            decision.setBuyerMessage(truncate(buyerMessage));
            decision.setReasonCode(reasonCode);
            decision.setDetail(truncate(detail));
            decisionMapper.insert(decision);
            log.info("决策日志已记录: account={}, sId={}, reason={}", accountId, sId, reasonCode);
        } catch (Exception e) {
            log.warn("决策日志写入失败（不影响主流程）: account={}, sId={}, reason={}, error={}",
                    accountId, sId, reasonCode, e.getMessage());
        }
    }

    /**
     * 分页查询决策日志
     *
     * @return list / totalCount / pageNum / pageSize
     */
    public Map<String, Object> queryDecisions(Long accountId, String reasonCode, int pageNum, int pageSize) {
        int safePageNum = Math.max(1, pageNum);
        int safePageSize = Math.min(Math.max(1, pageSize), 100);
        int offset = (safePageNum - 1) * safePageSize;
        var list = decisionMapper.selectPage(accountId, reasonCode, safePageSize, offset);
        int total = decisionMapper.countPage(accountId, reasonCode);
        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("totalCount", total);
        result.put("pageNum", safePageNum);
        result.put("pageSize", safePageSize);
        return result;
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_MESSAGE_LENGTH ? text : text.substring(0, MAX_MESSAGE_LENGTH);
    }
}
