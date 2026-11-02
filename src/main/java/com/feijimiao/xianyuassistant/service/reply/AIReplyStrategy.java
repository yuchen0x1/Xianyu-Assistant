package com.feijimiao.xianyuassistant.service.reply;

import com.feijimiao.xianyuassistant.entity.BargainSession;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import com.feijimiao.xianyuassistant.event.chatMessageEvent.ChatMessageData;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsInfoMapper;
import com.feijimiao.xianyuassistant.service.AIService;
import com.feijimiao.xianyuassistant.service.bargain.BargainDecision;
import com.feijimiao.xianyuassistant.service.bargain.BargainDetector;
import com.feijimiao.xianyuassistant.service.bargain.BargainPlanService;
import com.feijimiao.xianyuassistant.service.bargain.BargainSessionService;
import com.feijimiao.xianyuassistant.service.bo.RAGReplyResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class AIReplyStrategy implements ReplyStrategy {

    private static final int REPLY_TYPE_AI = 2;

    /** AI不可用/调用失败时返回的占位文案前缀，不能当作回复内容发给买家 */
    private static final String[] AI_ERROR_PREFIXES = {
            "AI服务暂未配置", "AI回复生成失败", "【AI服务错误】"
    };

    @Autowired
    private AIService aiService;

    @Autowired
    private XianyuGoodsConfigMapper goodsConfigMapper;

    @Autowired
    private XianyuGoodsInfoMapper goodsInfoMapper;

    @Autowired
    private BargainDetector bargainDetector;

    @Autowired
    private BargainSessionService bargainSessionService;

    @Autowired
    private BargainPlanService bargainPlanService;

    @Override
    public ReplyResult execute(List<ChatMessageData> messageList) {
        ChatMessageData lastMessage = messageList.get(messageList.size() - 1);
        Long accountId = lastMessage.getXianyuAccountId();
        String xyGoodsId = lastMessage.getXyGoodsId();

        String buyerMessage = messageList.stream()
                .map(ChatMessageData::getMsgContent)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        try {
            XianyuGoodsConfig goodsConfig = goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
            String fixedMaterial = goodsConfig != null ? goodsConfig.getFixedMaterial() : null;

            XianyuGoodsInfo goodsInfo = goodsInfoMapper.selectOne(
                    new LambdaQueryWrapper<XianyuGoodsInfo>().eq(XianyuGoodsInfo::getXyGoodId, xyGoodsId)
            );
            String goodsDetail = goodsInfo != null ? goodsInfo.getDetailInfo() : null;

            BargainRound bargainRound = prepareBargainRound(goodsConfig, goodsInfo, lastMessage, buyerMessage);

            RAGReplyResult result = aiService.chatByRAGWithPolicy(
                    buyerMessage, xyGoodsId, fixedMaterial, goodsDetail,
                    bargainRound != null ? bargainRound.promptSection : null);

            String replyContent = result != null ? result.getReplyContent() : null;
            if (isUsableReply(replyContent)) {
                if (bargainRound != null) {
                    bargainSessionService.recordRound(bargainRound.session, bargainRound.buyerOfferText, replyContent);
                    log.info("【账号{}】议价回复生成: xyGoodsId={}, 动作={}, 轮次={}/{}", accountId, xyGoodsId,
                            bargainRound.decision.getAction(), bargainRound.decision.getRound(),
                            bargainRound.decision.getMaxRounds());
                }
                return ReplyResult.of(Collections.singletonList(
                        ReplyResult.ReplyItem.text(replyContent, REPLY_TYPE_AI)
                ));
            }

            // AI不可用或调用失败时的议价降级：按已算好的决策用模板回复，不让策略落空
            if (bargainRound != null) {
                String fallback = bargainPlanService.buildFallbackReply(bargainRound.decision);
                bargainSessionService.recordRound(bargainRound.session, bargainRound.buyerOfferText, fallback);
                log.info("【账号{}】AI不可用，议价使用降级模板: xyGoodsId={}, 动作={}",
                        accountId, xyGoodsId, bargainRound.decision.getAction());
                return ReplyResult.of(Collections.singletonList(
                        ReplyResult.ReplyItem.text(fallback, REPLY_TYPE_AI)
                ));
            }
            return ReplyResult.fail();
        } catch (Exception e) {
            log.error("【账号{}】AI回复策略执行失败: xyGoodsId={}", accountId, xyGoodsId, e);
            return ReplyResult.fail();
        }
    }

    /**
     * 准备议价应对：议价未开启、保底价未配置或买家消息无议价意图时返回 null（走普通AI回复）。
     */
    private BargainRound prepareBargainRound(XianyuGoodsConfig goodsConfig, XianyuGoodsInfo goodsInfo,
                                             ChatMessageData lastMessage, String buyerMessage) {
        if (goodsConfig == null || goodsConfig.getBargainOn() == null || goodsConfig.getBargainOn() != 1) {
            return null;
        }
        // 保底价是议价决策的锚点，未配置时不启用议价，避免AI自行让价
        if (goodsConfig.getBargainFloorPrice() == null || goodsConfig.getBargainFloorPrice().isBlank()) {
            return null;
        }

        BargainDetector.BargainIntent intent = bargainDetector.detect(buyerMessage);
        if (!intent.isBargain()) {
            return null;
        }

        BigDecimal listPrice = parsePrice(goodsInfo != null ? goodsInfo.getSoldPrice() : null);
        BargainSession session = bargainSessionService.getOrCreateActiveSession(
                lastMessage.getXianyuAccountId(), lastMessage.getSId(),
                lastMessage.getXyGoodsId(), lastMessage.getSenderUserId());
        BargainDecision decision = bargainPlanService.decide(goodsConfig, session, intent, listPrice);

        BargainRound round = new BargainRound();
        round.session = session;
        round.decision = decision;
        round.promptSection = bargainPlanService.buildPromptSection(decision);
        round.buyerOfferText = decision.getBuyerOffer() != null
                ? decision.getBuyerOffer().stripTrailingZeros().toPlainString() : null;
        return round;
    }

    /**
     * 回复内容可用：非空白且不是AI错误占位文案（错误文案绝不能发给买家）。
     */
    private boolean isUsableReply(String replyContent) {
        if (replyContent == null || replyContent.isBlank()) {
            return false;
        }
        for (String prefix : AI_ERROR_PREFIXES) {
            if (replyContent.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private BigDecimal parsePrice(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            BigDecimal price = new BigDecimal(value.trim());
            return price.signum() > 0 ? price : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 一轮议价的执行上下文 */
    private static class BargainRound {
        BargainSession session;
        BargainDecision decision;
        String promptSection;
        String buyerOfferText;
    }
}
