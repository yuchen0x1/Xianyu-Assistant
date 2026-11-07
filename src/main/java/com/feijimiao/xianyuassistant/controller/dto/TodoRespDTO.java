package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 待办中心响应 DTO。
 *
 * <p>聚合卖家日常最常处理的四类待办：待发货订单、待回复会话、
 * 发货结果待人工复核、自动评价待确认。每类待办都携带账号信息与
 * 跳转所需的关键标识，前端可一键跳转到对应模块处理。</p>
 */
@Data
public class TodoRespDTO {

    /**
     * 各类待办计数摘要
     */
    private Summary summary = new Summary();

    /**
     * 待发货订单（对应"待发货订单"页）
     */
    private List<TodoItem> pendingShipment = new ArrayList<>();

    /**
     * 待回复会话（对应"消息管理"页）
     */
    private List<TodoItem> pendingReply = new ArrayList<>();

    /**
     * 发货结果待人工复核（对应"发货记录"页，state=0）
     */
    private List<TodoItem> pendingReview = new ArrayList<>();

    /**
     * 自动评价待确认（对应"自动评价"页，WAITING_CONFIRM）
     */
    private List<TodoItem> pendingEvaluation = new ArrayList<>();

    /**
     * 计数摘要
     */
    @Data
    public static class Summary {
        private int pendingShipmentCount;
        private int pendingReplyCount;
        private int pendingReviewCount;
        private int pendingEvaluationCount;

        public int getTotal() {
            return pendingShipmentCount + pendingReplyCount + pendingReviewCount + pendingEvaluationCount;
        }
    }

    /**
     * 单条待办项
     */
    @Data
    public static class TodoItem {
        /**
         * 所属闲鱼账号ID
         */
        private Long accountId;

        /**
         * 账号显示名（备注优先，其次昵称）
         */
        private String accountName;

        /**
         * 待办类型标识：pendingShipment / pendingReply / pendingReview / pendingEvaluation
         */
        private String type;

        /**
         * 主键标识：订单ID、会话ID(sid)、发货记录ID、评价记录ID
         */
        private String id;

        /**
         * 标题（商品标题 / 买家昵称 / 商品标题 / 订单号）
         */
        private String title;

        /**
         * 描述（买家昵称 / 最新消息内容 / 发货内容 / 评价内容）
         */
        private String description;

        /**
         * 关联商品ID（可选）
         */
        private String goodsId;

        /**
         * 时间（下单 / 最近消息 / 发货 / 计划评价时间），展示用
         */
        private String time;

        /**
         * 附加字段（金额、数量等），展示用
         */
        private String extra;
    }
}