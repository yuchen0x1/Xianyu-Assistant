package com.feijimiao.xianyuassistant.service.bargain;

import com.feijimiao.xianyuassistant.entity.BargainSession;
import com.feijimiao.xianyuassistant.mapper.BargainSessionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 议价会话服务：按 (账号, 会话ID) 维护议价轮次状态。
 *
 * <p>空闲超过 {@link #IDLE_RESET_HOURS} 小时或买家切换商品时自动重置轮次，
 * 避免隔天老对话继续消耗新议价的轮数。</p>
 */
@Slf4j
@Service
public class BargainSessionService {

    /** 会话空闲多久后重置议价轮次 */
    private static final int IDLE_RESET_HOURS = 48;

    @Autowired
    private BargainSessionMapper bargainSessionMapper;

    /**
     * 获取或创建有效的议价会话，必要时自动重置过期轮次。
     */
    public BargainSession getOrCreateActiveSession(Long accountId, String sId, String xyGoodsId, String buyerUserId) {
        BargainSession session = bargainSessionMapper.selectByAccountAndSessionId(accountId, sId);
        LocalDateTime now = LocalDateTime.now();

        if (session == null) {
            session = new BargainSession();
            session.setXianyuAccountId(accountId);
            session.setSId(sId);
            session.setXyGoodsId(xyGoodsId);
            session.setBuyerUserId(buyerUserId);
            session.setRoundsUsed(0);
            session.setStatus(BargainSession.STATUS_ACTIVE);
            session.setLastBargainAt(now);
            try {
                bargainSessionMapper.insert(session);
            } catch (Exception e) {
                // 并发下唯一索引冲突：读回已有会话
                session = bargainSessionMapper.selectByAccountAndSessionId(accountId, sId);
                if (session == null) {
                    throw e;
                }
            }
            return session;
        }

        boolean goodsSwitched = xyGoodsId != null && !xyGoodsId.equals(session.getXyGoodsId());
        boolean idleExpired = session.getLastBargainAt() != null
                && session.getLastBargainAt().isBefore(now.minusHours(IDLE_RESET_HOURS));
        if (goodsSwitched || idleExpired) {
            log.info("重置议价会话轮次: accountId={}, sId={}, 原因={}", accountId, sId,
                    goodsSwitched ? "切换商品" : "空闲超时");
            session.setRoundsUsed(0);
            session.setStatus(BargainSession.STATUS_ACTIVE);
            session.setLastOfferPrice(null);
            session.setLastReplyContent(null);
            if (goodsSwitched) {
                session.setXyGoodsId(xyGoodsId);
            }
        }
        if (buyerUserId != null && !buyerUserId.equals(session.getBuyerUserId())) {
            session.setBuyerUserId(buyerUserId);
        }
        return session;
    }

    /**
     * 记录一轮议价：轮数 +1，保存买家出价与卖方回复。
     */
    public void recordRound(BargainSession session, String buyerOffer, String replyContent) {
        session.setRoundsUsed((session.getRoundsUsed() == null ? 0 : session.getRoundsUsed()) + 1);
        session.setLastOfferPrice(buyerOffer);
        session.setLastReplyContent(replyContent);
        session.setLastBargainAt(LocalDateTime.now());
        try {
            bargainSessionMapper.update(session);
        } catch (Exception e) {
            log.warn("保存议价会话失败: sId={}, errorType={}", session.getSId(), e.getClass().getSimpleName());
        }
    }
}
