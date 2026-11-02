package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 获取商品列表请求DTO。
 */
@Data
public class ItemListReqDTO {
    /**
     * 账号ID。
     */
    @NotBlank(message = "账号ID不能为空")
    private String cookieId;

    /**
     * 页码，从 1 开始。
     */
    @Min(value = 1, message = "页码必须 >= 1")
    private Integer pageNumber = 1;

    /**
     * 每页数量。
     */
    @Min(value = 1, message = "页大小必须 >= 1")
    @Max(value = 200, message = "页大小不能超过 200")
    private Integer pageSize = 20;

    /**
     * 后续页需原样回传的服务端分页游标。
     */
    private Object nextPageModel;

    /**
     * 后续页需原样回传的服务端页序号或游标编号。
     */
    private Object nextPageNum;

    /**
     * 首屏动态下发的商品分组上下文，只接受服务端字段，不使用硬编码分组。
     */
    private Map<String, Object> groupContext;
}
