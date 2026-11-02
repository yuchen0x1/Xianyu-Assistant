package com.feijimiao.xianyuassistant.service.bargain;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 一轮议价的应对决策（由代码计算，AI 只负责把决策表达得自然）
 */
@Data
public class BargainDecision {

    public enum Action {
        /** 买家出价不低于保底价，直接同意成交 */
        ACCEPT,
        /** 报出让价后的价格 */
        COUNTER,
        /** 已到最大轮数或无可让空间，坚持保底价 */
        FIRM
    }

    private Action action;

    /** 本轮报价（COUNTER 时为我方要价；ACCEPT 时为同意的成交价） */
    private BigDecimal counterPrice;

    /** 当前议价轮次（从 1 开始） */
    private int round;

    /** 最大议价轮数 */
    private int maxRounds;

    /** 卖家保底价 */
    private BigDecimal floorPrice;

    /** 买家本轮出价，可能为空（纯砍价话术） */
    private BigDecimal buyerOffer;

    /** 商品当前售价 */
    private BigDecimal listPrice;

    /** 本轮实际让价步长 */
    private BigDecimal stepUsed;

    public boolean isAccept() {
        return action == Action.ACCEPT;
    }

    public boolean isFirm() {
        return action == Action.FIRM;
    }
}
