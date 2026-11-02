package com.feijimiao.xianyuassistant.service;

/**
 * Outcome of an HTTP delivery request.
 *
 * <p>UNKNOWN means the request may have reached Xianyu, but no authoritative
 * response was received. Callers must not retry automatically or release
 * reserved credentials until the order has been checked manually.</p>
 */
public enum ApiDeliveryResult {
    SUCCESS,
    FAILED,
    UNKNOWN,
    /**
     * 同一订单已有其他发货流程持有租约，本次调用未执行。
     *
     * <p>它既不是成功也不是失败：调用方不应据此改订单状态、不应重试、更不应释放卡密。</p>
     */
    SKIPPED
}
