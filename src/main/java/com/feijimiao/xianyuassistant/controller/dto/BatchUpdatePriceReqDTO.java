package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批量更新本地商品售价请求。
 */
@Data
public class BatchUpdatePriceReqDTO {

    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;

    @NotEmpty(message = "商品ID列表不能为空")
    @Size(max = 50, message = "单次最多操作50个商品")
    private List<String> xyGoodsIds;

    @NotNull(message = "商品价格不能为空")
    @DecimalMin(value = "0.01", message = "商品价格必须大于0")
    @Digits(integer = 9, fraction = 2, message = "商品价格最多保留2位小数")
    private BigDecimal soldPrice;
}
