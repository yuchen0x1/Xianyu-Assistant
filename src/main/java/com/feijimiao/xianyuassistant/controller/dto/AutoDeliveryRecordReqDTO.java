package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 获取自动发货记录请求DTO
 */
@Data
public class AutoDeliveryRecordReqDTO {
    
    /**
     * 闲鱼账号ID
     */
    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;
    
    /**
     * 商品ID（可选，不传则查询所有商品）
     */
    private String xyGoodsId;
    
    /**
     * 模糊搜索关键词，匹配商品名称、规格、买家、发货内容
     */
    private String keyword;

    /**
     * 发货执行状态：1-成功，0-结果未知/待人工核对，-1-失败。
     * 不传时查询全部记录。
     */
    private Integer state;
    
    /**
     * 页码
     */
    @Min(value = 1, message = "页码必须 >= 1")
    private Integer pageNum = 1;
    
    /**
     * 每页数量
     */
    @Min(value = 1, message = "页大小必须 >= 1")
    private Integer pageSize = 20;
}
