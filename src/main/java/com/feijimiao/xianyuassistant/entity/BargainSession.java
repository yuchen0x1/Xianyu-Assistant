package com.feijimiao.xianyuassistant.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 议价会话实体：按会话跟踪一场对话中的议价轮次
 */
@Data
public class BargainSession {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DEAL = "DEAL";
    public static final String STATUS_ENDED = "ENDED";

    private Long id;

    private Long xianyuAccountId;

    /**
     * 会话ID（账号内唯一标识一场对话，即消息的 sId）
     */
    private String sId;

    private String xyGoodsId;

    private String buyerUserId;

    /**
     * 已使用的议价轮数
     */
    private Integer roundsUsed;

    /**
     * 买家最近出价（元）
     */
    private String lastOfferPrice;

    /**
     * 卖方最近议价回复
     */
    private String lastReplyContent;

    /**
     * 状态：ACTIVE-议价中 / DEAL-已接受 / ENDED-已结束
     */
    private String status;

    /**
     * 最近一次议价时间（超过空闲窗口自动重置轮次）
     */
    private LocalDateTime lastBargainAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
