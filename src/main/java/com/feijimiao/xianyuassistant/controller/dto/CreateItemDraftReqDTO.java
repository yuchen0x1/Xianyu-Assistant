package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 新建本地商品资料草稿请求。
 */
@Data
public class CreateItemDraftReqDTO {
    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;

    @NotBlank(message = "商品标题不能为空")
    @Size(max = 200, message = "商品标题长度不能超过200")
    private String title;
    private String detailInfo;
    private BigDecimal soldPrice;
    /** 逗号分隔的图片 URL。 */
    private String infoPic;
}
