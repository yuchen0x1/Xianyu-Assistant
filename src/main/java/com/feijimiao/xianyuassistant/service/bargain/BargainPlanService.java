package com.feijimiao.xianyuassistant.service.bargain;

import com.feijimiao.xianyuassistant.entity.BargainSession;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 议价策略服务：由代码计算每轮应对决策（同意/还价/坚持底价），AI 只负责措辞。
 *
 * <p>决策规则：
 * <ul>
 *   <li>买家出价 ≥ 保底价 → ACCEPT</li>
 *   <li>已到最大轮数 → FIRM（坚持保底价不再让步）</li>
 *   <li>其余 → COUNTER，报价 = max(保底价, 售价 - 步长×轮次)；
 *       未配置步长时按 (售价-保底价)/最大轮数 均分让价；
 *       无售价时按 保底价 + 步长×(剩余轮数) 从高往低让</li>
 * </ul></p>
 */
@Slf4j
@Service
public class BargainPlanService {

    private static final int DEFAULT_MAX_ROUNDS = 3;

    private static final BigDecimal DEFAULT_STEP = new BigDecimal("5");

    private static final int PRICE_SCALE = 1;

    @Autowired
    private BargainDetector bargainDetector;

    /**
     * 计算一轮议价决策。
     *
     * @param session 当前议价会话（roundsUsed 为已用轮数）
     */
    public BargainDecision decide(XianyuGoodsConfig config, BargainSession session,
                                  BargainDetector.BargainIntent intent, BigDecimal listPrice) {
        BigDecimal floor = parsePrice(config.getBargainFloorPrice());
        BigDecimal step = parsePrice(config.getBargainStepPrice());
        int maxRounds = config.getBargainMaxRounds() != null && config.getBargainMaxRounds() > 0
                ? config.getBargainMaxRounds() : DEFAULT_MAX_ROUNDS;

        int round = (session.getRoundsUsed() == null ? 0 : session.getRoundsUsed()) + 1;
        BigDecimal buyerOffer = intent.getOfferedPrice();

        BargainDecision decision = new BargainDecision();
        decision.setRound(round);
        decision.setMaxRounds(maxRounds);
        decision.setFloorPrice(floor);
        decision.setBuyerOffer(buyerOffer);
        decision.setListPrice(listPrice);

        // 买家只给了让价差额没给出价：按售价换算
        if (buyerOffer == null && intent.getDeduction() != null && listPrice != null) {
            buyerOffer = listPrice.subtract(intent.getDeduction());
            decision.setBuyerOffer(buyerOffer);
        }

        if (buyerOffer != null && floor != null && buyerOffer.compareTo(floor) >= 0) {
            decision.setAction(BargainDecision.Action.ACCEPT);
            decision.setCounterPrice(buyerOffer);
            return decision;
        }

        if (round >= maxRounds) {
            decision.setAction(BargainDecision.Action.FIRM);
            decision.setCounterPrice(floor);
            return decision;
        }

        BigDecimal counter = computeCounter(floor, step, maxRounds, round, listPrice);
        decision.setStepUsed(step);
        if (buyerOffer != null && counter.compareTo(buyerOffer) <= 0) {
            // 本轮可让到的价格已不高于买家出价，直接同意，避免来回拉扯
            decision.setAction(BargainDecision.Action.ACCEPT);
            decision.setCounterPrice(buyerOffer);
        } else {
            decision.setAction(BargainDecision.Action.COUNTER);
            decision.setCounterPrice(counter);
        }
        return decision;
    }

    /**
     * 构建追加到 AI 系统提示词的议价策略段。
     */
    public String buildPromptSection(BargainDecision decision) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【议价策略（仅当买家砍价或询问最低价时应用，其他话题忽略本段）】\n");
        if (decision.getListPrice() != null) {
            sb.append("- 商品当前售价：").append(stripTrailingZero(decision.getListPrice())).append("元\n");
        }
        if (decision.getFloorPrice() != null) {
            sb.append("- 卖家保底价：").append(stripTrailingZero(decision.getFloorPrice()))
                    .append("元，绝对不能同意低于此价成交\n");
        }
        sb.append("- 当前是第 ").append(decision.getRound()).append("/").append(decision.getMaxRounds())
                .append(" 轮议价\n");

        switch (decision.getAction()) {
            case ACCEPT -> sb.append("- 本轮应对：同意成交。买家出价 ")
                    .append(stripTrailingZero(decision.getCounterPrice()))
                    .append("元已达可接受范围，爽快同意并引导直接拍下\n");
            case COUNTER -> sb.append("- 本轮应对：还价。明确报出 ")
                    .append(stripTrailingZero(decision.getCounterPrice()))
                    .append("元，语气自然可以小幅浮动措辞但报出的数字必须是这个价\n");
            case FIRM -> sb.append("- 本轮应对：坚持底价。礼貌但明确表示")
                    .append(decision.getFloorPrice() != null
                            ? stripTrailingZero(decision.getFloorPrice()) + "元已经是最低价" : "价格已经很实在")
                    .append("，不能再低了，可表达诚意挽留\n");
        }
        sb.append("- 要求：口语化、简短（50字内）、像真实卖家，不要出现\"策略\"\"系统\"\"轮数\"\"保底\"等字眼");
        return sb.toString();
    }

    /**
     * AI 不可用或调用失败时的降级回复模板，保证议价决策仍能落地。
     */
    public String buildFallbackReply(BargainDecision decision) {
        String price = decision.getCounterPrice() != null ? stripTrailingZero(decision.getCounterPrice()) : null;
        return switch (decision.getAction()) {
            case ACCEPT -> "可以哦，" + price + "元直接拍就行～";
            case COUNTER -> "这样吧，最低" + price + "元，能接受的话直接拍～";
            case FIRM -> decision.getFloorPrice() != null
                    ? "真的到底价啦，" + stripTrailingZero(decision.getFloorPrice()) + "元已经很实在了，喜欢直接拍哈～"
                    : "价格已经很实在啦，喜欢的话直接拍哈～";
        };
    }

    private BigDecimal computeCounter(BigDecimal floor, BigDecimal step, int maxRounds, int round, BigDecimal listPrice) {
        BigDecimal effectiveStep = step != null ? step : defaultStep(floor, listPrice, maxRounds);
        BigDecimal counter;
        if (listPrice != null) {
            counter = listPrice.subtract(effectiveStep.multiply(BigDecimal.valueOf(round)));
            if (floor != null) {
                counter = counter.max(floor);
            } else if (counter.signum() <= 0) {
                counter = BigDecimal.ONE;
            }
        } else {
            // 无售价锚点：从保底价上方的步长梯度递减
            BigDecimal headroom = effectiveStep.multiply(BigDecimal.valueOf(maxRounds - round));
            counter = (floor != null ? floor : BigDecimal.ONE).add(headroom);
        }
        return counter.setScale(PRICE_SCALE, RoundingMode.DOWN).max(BigDecimal.ONE);
    }

    /**
     * 未配置步长时按 (售价-保底价)/最大轮数 均分让价；无售价时用默认步长。
     */
    private BigDecimal defaultStep(BigDecimal floor, BigDecimal listPrice, int maxRounds) {
        if (listPrice != null && floor != null
                && listPrice.compareTo(floor) > 0
                && listPrice.subtract(floor).compareTo(BigDecimal.ONE) > 0) {
            return listPrice.subtract(floor)
                    .divide(BigDecimal.valueOf(maxRounds), 1, RoundingMode.UP);
        }
        return DEFAULT_STEP;
    }

    private BigDecimal parsePrice(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            BigDecimal price = new BigDecimal(value.trim());
            return price.signum() > 0 ? price : null;
        } catch (NumberFormatException e) {
            log.warn("议价价格配置格式错误: {}", value);
            return null;
        }
    }

    private String stripTrailingZero(BigDecimal price) {
        return price.stripTrailingZeros().toPlainString();
    }
}
