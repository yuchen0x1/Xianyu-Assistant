package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @author IAMLZY
 * @date 2026/4/12 00:31
 * @description
 */
@Data
public class ChatWithAIReqDTO {
    /**
     * 消息内容
     */
    @NotBlank(message = "消息内容不能为空")
    private String msg;
    /**
     * 商品id
     */
    @NotBlank(message = "商品ID不能为空")
    private String goodsId;
}
