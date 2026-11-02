package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 从数据库获取商品列表请求DTO
 */
@Data
public class ItemListFromDbReqDTO {
    
    /**
     * 只显示在售商品
     * true=只显示在售(status=0), false/null=显示全部
     * 默认true
     */
    private Boolean onlyOnSale = true;

    /**
     * 可选的精确状态筛选。设置后优先于 onlyOnSale。
     */
    private Integer status;
    
    /**
     * 闲鱼账号ID（可选）
     */
    private Long xianyuAccountId;
    
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
}
