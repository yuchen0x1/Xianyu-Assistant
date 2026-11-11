package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

/**
 * 批量擦亮商品请求DTO
 */
@Data
public class BatchPolishItemsReqDTO {
    @NotNull(message = "账号ID不能为空")
    private Long xianyuAccountId;

    /**
     * 可选指定商品ID列表；若为空则擦亮该账号本地库中所有在售商品。
     *
     * <p>上限 50 件与批量下架/批量改价保持一致：接口在请求线程内逐件调用平台 API，
     * 件间间隔 1 秒，超量会让单次请求必然超过前端 30 秒超时。</p>
     */
    @Size(max = 50, message = "单次最多批量擦亮50个商品")
    private List<String> xyGoodsIds;
}
