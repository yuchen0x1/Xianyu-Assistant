package com.feijimiao.xianyuassistant.service.delivery;

/**
 * Tracks resources reserved while preparing one delivery unit.
 *
 * <p>三种终态，语义必须严格区分，否则会造成「同一张卡卖两次」或「卡密静默泄漏」：</p>
 * <ul>
 *   <li>{@link #commit()} 确认已交付：资源正式消耗（卡密写入交付确认时间）。</li>
 *   <li>{@link #rollback()} 确认未交付：资源释放回库存（卡密恢复可用）。</li>
 *   <li>{@link #hold()} 结果未知：既不能消耗也不能释放，冻结在预占态，交给滞留巡检或人工核对。</li>
 * </ul>
 */
public final class DeliveryReservation {

    private final Runnable rollbackAction;
    private final Runnable commitAction;
    private boolean completed;

    private DeliveryReservation(Runnable rollbackAction, Runnable commitAction) {
        this.rollbackAction = rollbackAction;
        this.commitAction = commitAction;
    }

    public static DeliveryReservation none() {
        return new DeliveryReservation(() -> { }, () -> { });
    }

    public static DeliveryReservation rollbackWith(Runnable rollbackAction) {
        if (rollbackAction == null) {
            throw new IllegalArgumentException("rollbackAction不能为空");
        }
        return new DeliveryReservation(rollbackAction, () -> { });
    }

    /**
     * 同时具备释放与确认动作：发货成功走确认，明确失败走释放，结果未知则什么也不做。
     */
    public static DeliveryReservation rollbackAndCommitWith(Runnable rollbackAction, Runnable commitAction) {
        if (rollbackAction == null) {
            throw new IllegalArgumentException("rollbackAction不能为空");
        }
        if (commitAction == null) {
            throw new IllegalArgumentException("commitAction不能为空");
        }
        return new DeliveryReservation(rollbackAction, commitAction);
    }

    /**
     * 只有确认动作、没有释放动作：用于复用的历史预占，任何失败都不得把它退回库存。
     */
    public static DeliveryReservation commitWith(Runnable commitAction) {
        if (commitAction == null) {
            throw new IllegalArgumentException("commitAction不能为空");
        }
        return new DeliveryReservation(() -> { }, commitAction);
    }

    public synchronized void commit() {
        if (completed) {
            return;
        }
        commitAction.run();
        completed = true;
    }

    /**
     * 冻结为「结果未知」：保留资源、不允许再被释放，也不标记为已交付。
     */
    public synchronized void hold() {
        completed = true;
    }

    public synchronized void rollback() {
        if (completed) {
            return;
        }
        rollbackAction.run();
        completed = true;
    }
}
