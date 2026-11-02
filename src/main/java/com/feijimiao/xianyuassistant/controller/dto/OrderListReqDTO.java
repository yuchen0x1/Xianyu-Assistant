package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 订单列表请求DTO
 */
@Data
public class OrderListReqDTO {

    private Long xianyuAccountId;

    private String xyGoodsId;

    /**
     * 订单状态（2=待发货, 1=已发货, -1=发货失败）
     */
    private Integer orderStatus;

    private String keyword;

    @Min(value = 1, message = "页码必须 >= 1")
    private Integer pageNum = 1;

    @Min(value = 1, message = "页大小必须 >= 1")
    @Max(value = 200, message = "页大小不能超过 200")
    private Integer pageSize = 20;
}
