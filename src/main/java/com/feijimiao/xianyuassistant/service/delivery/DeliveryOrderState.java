package com.feijimiao.xianyuassistant.service.delivery;

/**
 * 订单发货记录的状态常量与人工核对标记。
 *
 * <p>数据库沿用 state 的整型取值，不新增列；调度器通过
 * {@link #MANUAL_REVIEW_MARKER} 识别“必须人工核对、不得自动重试”的记录。</p>
 */
public final class DeliveryOrderState {

    /** 待处理/结果未确认 */
    public static final int PENDING = 0;

    /** 已确认发货成功 */
    public static final int DELIVERED = 1;

    /** 已确认发货失败（可安全重试） */
    public static final int FAILED = -1;

    /** 人工核对标记：失败原因中包含该词时，调度器不得自动重试 */
    public static final String MANUAL_REVIEW_MARKER = "结果未知";

    /** 消息已送达但订单状态落库失败时的待核对原因 */
    public static final String STATE_SAVE_FAILED_REASON =
            MANUAL_REVIEW_MARKER + "：发货已成功但订单状态落库失败，请人工核对；卡密已提交，请勿重发";

    private DeliveryOrderState() {
    }

    public static boolean isAwaitingManualReview(Integer state, String failReason) {
        return Integer.valueOf(PENDING).equals(state)
                && failReason != null
                && failReason.contains(MANUAL_REVIEW_MARKER);
    }
}
