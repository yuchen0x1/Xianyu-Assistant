package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @author IAMLZY
 * @date 2026/4/12 00:42
 * @description
 */
@Data
public class PutNewDataToRAGReqDTO {
    @NotBlank(message = "资料内容不能为空")
    String content;

    @NotBlank(message = "商品ID不能为空")
    String goodsId;
}
