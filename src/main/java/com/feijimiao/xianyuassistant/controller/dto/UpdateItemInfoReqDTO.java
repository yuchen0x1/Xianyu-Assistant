package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 更新商品资料请求。
 *
 * <p>该接口只更新助手本地商品资料，不伪装成闲鱼远程编辑接口。</p>
 */
@Data
public class UpdateItemInfoReqDTO {
    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;

    @NotBlank(message = "商品ID不能为空")
    private String xyGoodsId;

    private String title;
    private String detailInfo;
    private BigDecimal soldPrice;
    /** 逗号分隔的图片 URL，保存为商品详情图 JSON。 */
    private String infoPic;
}
