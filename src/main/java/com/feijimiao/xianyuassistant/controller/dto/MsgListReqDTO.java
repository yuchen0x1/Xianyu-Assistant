package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 消息列表查询请求DTO
 */
@Data
public class MsgListReqDTO {
    
    /**
     * 闲鱼账号ID（必选）
     */
    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;
    
    /**
     * 闲鱼商品ID（可选）
     */
    private String xyGoodsId;
    
    /**
     * 页码，从1开始
     * 默认1
     */
    @Min(value = 1, message = "页码必须 >= 1")
    private Integer pageNum = 1;
    
    /**
     * 每页数量
     * 默认20
     */
    @Min(value = 1, message = "页大小必须 >= 1")
    @Max(value = 200, message = "页大小不能超过 200")
    private Integer pageSize = 20;
    
    /**
     * 是否过滤当前账号的消息
     * true: 不显示当前账号发送的消息
     * false: 显示所有消息（默认）
     */
    private Boolean filterCurrentAccount = false;
}
