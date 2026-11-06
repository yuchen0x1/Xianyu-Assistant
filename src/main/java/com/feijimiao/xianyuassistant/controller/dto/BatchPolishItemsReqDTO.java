package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;

/**
 * 批量擦亮商品请求DTO
 */
@Data
public class BatchPolishItemsReqDTO {
    @NotNull(message = "账号ID不能为空")
    private Long xianyuAccountId;

    /** 可选指定商品ID列表；若为空则擦亮该账号本地库中所有在售商品 */
    private List<String> xyGoodsIds;
}
