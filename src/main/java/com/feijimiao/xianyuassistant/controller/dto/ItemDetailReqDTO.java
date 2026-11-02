package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 获取商品详情请求DTO。
 */
@Data
public class ItemDetailReqDTO {

    /**
     * 闲鱼商品 ID。
     */
    @NotBlank(message = "商品ID不能为空")
    private String xyGoodId;

    /**
     * 可选账号标识。未传时由服务端使用商品关联账号，避免前端重复持有账号 Cookie。
     */
    private String cookieId;
}
